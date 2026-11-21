(module
  (func (export "apply") (param i32 i32) (result i32)
    local.get 0
    i32.eqz
    local.get 1
    i32.and
  )
  (func (export "activateMedia") (param i32) (result i32)
    (i32.eqz (local.get 0)))
  (func (export "mediaKey") (param i32) (result i32)
    (select (i32.const 126)
      (select (i32.const 127)
      (select (i32.const 87)
      (select (i32.const 88)
      (select (i32.const 126)
      (select (i32.const 127)
      (select (i32.const 87)
      (select (i32.const 88)
      (i32.const 0) (i32.eq (local.get 0) (i32.const 44))) (i32.eq (local.get 0) (i32.const 43))) (i32.eq (local.get 0) (i32.const 42))) (i32.eq (local.get 0) (i32.const 41))) (i32.eq (local.get 0) (i32.const 4))) (i32.eq (local.get 0) (i32.const 3))) (i32.eq (local.get 0) (i32.const 2))) (i32.eq (local.get 0) (i32.const 1))))
)
