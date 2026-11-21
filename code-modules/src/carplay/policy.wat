(module
  (func (export "apply") (param i32 i32 i32 i32) (result i32)
    local.get 2
    local.get 1
    i32.mul
    local.get 3
    local.get 0
    i32.mul
    i32.le_s
  )
  (func (export "mediaButton") (param i32) (result i32)
    (select (i32.const 1) (select (i32.const 2) (select (i32.const 3) (select (i32.const 4) (i32.const 0) (i32.eq (local.get 0) (i32.const 88))) (i32.eq (local.get 0) (i32.const 87))) (i32.eq (local.get 0) (i32.const 127))) (i32.eq (local.get 0) (i32.const 126))))
  (func (export "connectAction") (param i32) (result i32)
    ;; Bit 0: MFi ready, bit 1: wireless, bit 2: reconnect.
    ;; 0 authenticate, 1 wired discovery, 2 start wireless, 3 restart wireless.
    (select
      (select (select (i32.const 3) (i32.const 2) (i32.and (local.get 0) (i32.const 4)))
        (i32.const 1) (i32.and (local.get 0) (i32.const 2)))
      (i32.const 0) (i32.and (local.get 0) (i32.const 1))))
  (func (export "windowHeight") (param i32) (result i32)
    (select (i32.const 240) (i32.sub (i32.const 876) (local.get 0)) (i32.gt_s (local.get 0) (i32.const 636))))
  ;; mode 0: one transfer, 1: fill, 2: one USB read capped at 64 KiB, 3: one frame.
  (func (export "transfer") (param $mode i32) (param $remaining i32) (param $done i32) (result i32)
    (select
      (select (i32.const 65536) (local.get $remaining)
        (i32.and (i32.eq (local.get $mode) (i32.const 2))
                 (i32.gt_s (local.get $remaining) (i32.const 65536))))
      (i32.const 0)
      (i32.or (i32.eqz (local.get $done)) (i32.eq (local.get $mode) (i32.const 1)))))
  ;; Path 0: TCP, 1: USB. Select the endpoint, configure, then acquire.
  (func (export "openTransport") (param $path i32) (param $position i32) (result i32)
    (local.get $position))
  (func (export "usbRetry") (param i32) (result i32)
    (i32.const 0))
)
