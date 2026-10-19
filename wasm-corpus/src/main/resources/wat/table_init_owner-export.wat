;; Every slot of the exported table starts out holding $answer. Nothing calls
;; this module, so only the table says whose function the slots hold.
(module
  (global $g i32 (i32.const 42))

  (func $answer (result i32)
    global.get $g)

  (table (export "tab") 2 funcref (ref.func $answer))
)
