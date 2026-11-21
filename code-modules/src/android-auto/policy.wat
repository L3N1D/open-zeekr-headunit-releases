(module
  (func (export "apply") (param i32 i32) (result i32)
    local.get 0
    i32.const 1
    local.get 0
    i32.const 1
    i32.ge_s
    local.get 0
    i32.const 3
    i32.le_s
    i32.and
    select
    i32.const 3
    local.get 1
    select
  )
  (func (export "versionRequests") (param i32) (result i32)
    (select (i32.const 3) (i32.const 1) (local.get 0)))
  (func (export "handshakeFrame") (param i32) (result i32)
    (select (i32.const 1)
      (select (i32.const 2) (i32.const 0)
        (i32.or (i32.eq (local.get 0) (i32.const 65535)) (i32.eq (local.get 0) (i32.const 15))))
      (i32.eq (local.get 0) (i32.const 2))))
  (func (export "readError") (param i32 i32 i32) (result i32)
    (i32.and (i32.lt_s (local.get 0) (i32.const 0)) (i32.or (i32.eqz (local.get 1)) (local.get 2))))
  (func (export "versionFailure") (param i32 i32) (result i32)
    (select (i32.const 2) (i32.eqz (local.get 0)) (local.get 1)))
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
    (select (i32.const 1000) (i32.const 0) (i32.lt_s (local.get 0) (i32.const 3))))
)
