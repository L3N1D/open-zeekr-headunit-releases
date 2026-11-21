(module
  (func (export "apply") (param i32) (result i32)
    local.get 0
    i32.const 120
    local.get 0
    i32.const 120
    i32.ge_s
    select
    i32.const 320
    local.get 0
    i32.const 320
    i32.le_s
    select
  )
  (func (export "audioRoute") (param i32) (result i32)
    (select (i32.const 2) (select (i32.const 3) (i32.const 1) (i32.eq (local.get 0) (i32.const 3))) (i32.eq (local.get 0) (i32.const 2))))
  (func (export "openStep") (param i32) (result i32)
    ;; Index in the twelve-step USB startup sequence.
    (local.get 0))
  (func (export "pcmFormat") (param i32) (result i32)
    (select (i32.sub (local.get 0) (i32.const 2)) (i32.const 0) (i32.and (i32.ge_s (local.get 0) (i32.const 3)) (i32.le_s (local.get 0) (i32.const 7)))))
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
