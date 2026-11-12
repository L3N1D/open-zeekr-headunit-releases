import io,json,struct,unittest
from bridge import read_message,MAX_MESSAGE,Device
class FramingTest(unittest.TestCase):
    def test_multiple_messages(self):
        data=json.dumps({'op':'open'}).encode(); f=io.BytesIO((struct.pack('!I',len(data))+data)*2)
        self.assertEqual(read_message(f),{'op':'open'});self.assertEqual(read_message(f),{'op':'open'})
    def test_bounds(self):
        for size in (0,MAX_MESSAGE+1,0xffffffff):
            with self.assertRaises(ValueError):read_message(io.BytesIO(struct.pack('!I',size)))
    def test_truncated(self):
        with self.assertRaises(EOFError):read_message(io.BytesIO(b'\x00\x00\x00\x02{'))
    def test_no_arbitrary_usb_commands(self):
        d=Device(None)
        for op in ('control','reset','shell','grant','write'):
            with self.assertRaises(ValueError):d.call({'op':op})
    def test_requires_open(self):
        with self.assertRaises(RuntimeError):Device(None).call({'op':'mux_read'})

class EthernetStartupTest(unittest.TestCase):
    def test_checks_ethernet_only_after_selecting_configuration(self):
        from unittest.mock import MagicMock, patch
        from types import SimpleNamespace
        events=[]
        mux=SimpleNamespace(bInterfaceClass=255,bInterfaceSubClass=254,bInterfaceNumber=1)
        net=SimpleNamespace(bInterfaceClass=255,bInterfaceSubClass=253,bInterfaceNumber=2,bAlternateSetting=1)
        config=MagicMock();config.bConfigurationValue=4;config.__iter__.side_effect=lambda:iter([mux,net])
        dev=MagicMock();dev.__iter__.side_effect=lambda:iter([config])
        dev.get_active_configuration.return_value.bConfigurationValue=1
        dev.set_configuration.side_effect=lambda n:events.append(('config',n))
        dev.is_kernel_driver_active.return_value=False
        with patch('bridge.pair',return_value=(object(),object())), \
             patch('bridge.usb.util.get_string',return_value='test-serial'), \
             patch('bridge.verify_ethernet_interface',side_effect=lambda s,n:events.append(('verify',n))), \
             patch('bridge.usb.util.claim_interface') as claim, \
             patch('scapy.arch.bpf.supersocket.L2bpfSocket'):
            device=Device(dev,'en5');device.prepare()
        self.assertEqual(events,[('config',4),('verify','en5')])
        claim.assert_called_once_with(dev,1)

if __name__=='__main__':unittest.main()
