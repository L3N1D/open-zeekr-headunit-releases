(module
  (func (export "apply") (param i32 i32 i32 i32) (result i32)
    local.get 0
    i32.const 0
    i32.gt_s
    local.get 1
    i32.const 0
    i32.gt_s
    i32.and
    local.get 2
    i32.const 0
    i32.gt_s
    i32.and
    local.get 3
    i32.const 0
    i32.gt_s
    i32.and
    local.get 2
    local.get 0
    i32.sub
    i32.const 100
    i32.ge_s
    local.get 0
    local.get 2
    i32.sub
    i32.const 100
    i32.ge_s
    i32.or
    i32.and
    local.get 3
    local.get 1
    i32.sub
    i32.const 32
    i32.le_s
    i32.and
    local.get 1
    local.get 3
    i32.sub
    i32.const 32
    i32.le_s
    i32.and
  )
  (func (export "resizeDuration") (param i32) (result i32)
    (i32.const 450))
  (func (export "widgetHeight") (param i32 i32 i32 i32) (result i32)
    (select (local.get 1) (select (select (local.get 0) (local.get 2) (i32.lt_s (local.get 0) (local.get 2))) (local.get 3) (i32.gt_s (local.get 3) (select (local.get 0) (local.get 2) (i32.lt_s (local.get 0) (local.get 2))))) (i32.lt_s (local.get 3) (local.get 1))))
  (func (export "widgetRoom") (param i32 i32 i32 i32) (result i32)
    (i32.sub (i32.sub (i32.sub (local.get 0) (local.get 1)) (local.get 2)) (i32.mul (local.get 3) (i32.const 2))))
)
