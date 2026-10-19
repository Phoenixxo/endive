;; Its own function has the same index as the exporter's $answer, but returns -1.
(module
  (type $i32 (func (result i32)))

  (import "test" "tab" (table 2 funcref))

  (global $g i32 (i32.const -1))

  (func $own (result i32)
    global.get $g)

  (func (export "call") (param i32) (result i32)
    local.get 0
    call_indirect (type $i32))
)
