#!/usr/bin/env python3
"""Loopback-only USB bridge for CarPlay lab tests. Never selects Android Auto devices.
Requires pyusb + libusb. Start with --serial from --list; forward with adb reverse.
Binary-safe, bounded JSON RPC. Only fixed projection endpoints are exposed, not arbitrary USB commands.
"""
import argparse, base64, json, socketserver, struct, threading, os, plistlib, subprocess, time
import usb.core, usb.util, usb.backend.libusb1
MAX_MESSAGE=256*1024

def verify_ethernet_interface(serial, name):
    tree=plistlib.loads(subprocess.check_output(['ioreg','-a','-p','IOService','-r','-n','iPhone']))
    def descendants(node):
        yield node
        for child in node.get('IORegistryEntryChildren',[]):yield from descendants(child)
    roots=[r for r in tree if r.get('USB Serial Number',r.get('kUSBSerialNumberString'))==serial]
    if not any(n.get('IORegistryEntryName')==name and n.get('IOObjectClass')=='IOEthernetInterface' for r in roots for n in descendants(r)):
        raise ValueError('Ethernet interface does not belong to the selected USB device')


def exact(stream, count):
    data=bytearray()
    while len(data)<count:
        chunk=stream.read(count-len(data))
        if not chunk: raise EOFError()
        data.extend(chunk)
    return bytes(data)

def read_message(stream):
    size=struct.unpack('!I',exact(stream,4))[0]
    if not 0<size<=MAX_MESSAGE: raise ValueError('Invalid RPC length')
    value=json.loads(exact(stream,size))
    if not isinstance(value,dict): raise ValueError('Expected JSON object')
    return value

def pair(iface):
    endpoints=[e for e in iface if e.bmAttributes&3==2]
    ins=[e for e in endpoints if e.bEndpointAddress&0x80]
    outs=[e for e in endpoints if not e.bEndpointAddress&0x80]
    return (ins[0],outs[0]) if len(ins)==len(outs)==1 else None

class Device:
    def __init__(self, dev, ethernet_interface=None):
        self.dev=dev;self.lock=threading.Lock();self.pipes={};self.mode=None;self.detached=[];self.ethernet_interface=ethernet_interface;self.bpf=None
        self.io_locks={name:threading.Lock() for name in ('mux_read','mux_write','net_read','net_write')}
    def prepare(self):
        if self.pipes:return
        configs=list(self.dev)
        choices=[]
        for config in configs:
            mux=next((i for i in config if (i.bInterfaceClass,i.bInterfaceSubClass)==(255,254) and pair(i)),None)
            cdc=next((i for i in config if (i.bInterfaceClass,i.bInterfaceSubClass)==(2,13)),None)
            net=next((i for i in config if i.bInterfaceClass==10 and pair(i)),None) if cdc else None
            mode='ncm'
            if net is None:
                net=next((i for i in config if (i.bInterfaceClass,i.bInterfaceSubClass)==(255,253) and pair(i)),None)
                mode='ethernet'
            if mux is not None and net is not None:choices.append((config,mux,net,cdc,mode))
        if not choices:raise RuntimeError('No USBMUX + NCM/Apple Ethernet configuration')
        config,mux,net,cdc,self.mode=sorted(choices,key=lambda x:x[4]!='ncm')[0]
        print(f'Preparing config={config.bConfigurationValue}, mode={self.mode}',flush=True)
        if self.dev.get_active_configuration().bConfigurationValue != config.bConfigurationValue:
            self.dev.set_configuration(config.bConfigurationValue)
        try:
            # Ethernet exists only after selecting configuration 4. Do not reject a freshly
            # plugged device in configuration 1 before giving its driver time to create it.
            if self.mode=='ethernet' and self.ethernet_interface:
                serial=usb.util.get_string(self.dev,self.dev.iSerialNumber)
                deadline=time.monotonic()+4
                while True:
                    try:
                        verify_ethernet_interface(serial,self.ethernet_interface)
                        break
                    except ValueError:
                        if time.monotonic()>=deadline:raise
                        time.sleep(0.2)
            interfaces=(mux,) if self.mode=='ethernet' and self.ethernet_interface else (mux,net,cdc)
            for i in {x.bInterfaceNumber:x for x in interfaces if x is not None}.values():
                print(f'Claiming USB interface {i.bInterfaceNumber}',flush=True)
                if self.dev.is_kernel_driver_active(i.bInterfaceNumber):
                    if os.geteuid()!=0:
                        raise PermissionError('macOS driver owns this USB interface; run this bridge with sudo for temporary device-only detach')
                    self.dev.detach_kernel_driver(i.bInterfaceNumber)
                    self.detached.append(i.bInterfaceNumber)
                usb.util.claim_interface(self.dev,i.bInterfaceNumber)
            if self.mode=='ethernet' and self.ethernet_interface:
                from scapy.arch.bpf.supersocket import L2bpfSocket
                self.bpf=L2bpfSocket(iface=self.ethernet_interface, promisc=True)
            else:
                self.dev.set_interface_altsetting(net.bInterfaceNumber,net.bAlternateSetting)
            self.pipes={'mux':pair(mux),'net':pair(net)}
            print(f'USB config={config.bConfigurationValue} mux={mux.bInterfaceNumber} net={net.bInterfaceNumber}/{net.bAlternateSetting} mode={self.mode}',flush=True)
        except Exception:
            self.close();self.pipes={};raise
    def call(self, req):
        op=req.get('op')
        if op=='open':
            with self.lock:self.prepare()
            return {'mode':self.mode}
        if op not in self.io_locks:raise ValueError('Unsupported operation')
        if not self.pipes:raise RuntimeError('Session not opened')
        name,direction=op.split('_');ep=self.pipes[name][0 if direction=='read' else 1]
        timeout=max(1,min(int(req.get('timeout',1000)),5000))
        with self.io_locks[op]:
            if name=='net' and self.bpf is not None:
                from scapy.all import Ether
                import select
                if direction=='read':
                    if not self.bpf.select([self.bpf],timeout/1000): return {'timeout':True}
                    packet=self.bpf.recv()
                    if packet is None:return {'timeout':True}
                    return {'data':base64.b64encode(bytes(packet)).decode()}
                data=base64.b64decode(req['data'],validate=True)
                if not 14<=len(data)<=65536:raise ValueError('Invalid Ethernet size')
                self.bpf.send(Ether(data))
                return {'written':len(data)}
            if direction=='read':
                try:data=bytes(ep.read(65536,timeout=timeout))
                except usb.core.USBTimeoutError:return {'timeout':True}
                return {'data':base64.b64encode(data).decode()}
            data=base64.b64decode(req['data'],validate=True)
            if not 0<len(data)<=65536:raise ValueError('Invalid transfer size')
            try:count=ep.write(data,timeout=timeout)
            except usb.core.USBTimeoutError:
                if name=='net':return {'timeout':True}
                raise
            if count!=len(data):raise RuntimeError('Partial USB write; session must restart')
            return {'written':count}
    def close(self):
        if self.bpf is not None:self.bpf.close();self.bpf=None
        usb.util.dispose_resources(self.dev)
        for interface in self.detached:
            try:self.dev.attach_kernel_driver(interface)
            except (usb.core.USBError,NotImplementedError):pass
        self.detached.clear()

class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        self.connection.settimeout(120)
        try:
            while True:
                req=read_message(self.rfile)
                try:result={'ok':True,**self.server.device.call(req)}
                except Exception as e:
                    result={'ok':False,'error':f'{type(e).__name__}: {e}'}
                    print(result['error'],flush=True)
                data=json.dumps(result).encode()
                self.wfile.write(struct.pack('!I',len(data))+data);self.wfile.flush()
        except (EOFError,ConnectionError,TimeoutError):pass
        except Exception as e:print('RPC rejected:',type(e).__name__,flush=True)
class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address=True
    daemon_threads=True

def main():
    p=argparse.ArgumentParser();p.add_argument('--serial');p.add_argument('--list',action='store_true');p.add_argument('--port',type=int,default=5278);p.add_argument('--ethernet-interface');args=p.parse_args()
    backend=usb.backend.libusb1.get_backend(find_library=lambda _: '/opt/homebrew/lib/libusb-1.0.dylib')
    devices=list(usb.core.find(find_all=True,idVendor=0x05ac,backend=backend))
    if args.list:
        for d in devices:print(f'{d.idVendor:04x}:{d.idProduct:04x} serial={usb.util.get_string(d,d.iSerialNumber)}')
        return
    if not args.serial:p.error('--serial is required; use --list first')
    devices=[d for d in devices if usb.util.get_string(d,d.iSerialNumber)==args.serial]
    if len(devices)!=1:raise RuntimeError('Expected exactly one matching Apple-mode device')
    device=Device(devices[0],args.ethernet_interface)
    try:
        with Server(('127.0.0.1',args.port),Handler) as server:
            server.device=device;print(f'CarPlay USB bridge on 127.0.0.1:{args.port}',flush=True);server.serve_forever()
    finally:device.close()
if __name__=='__main__':main()
