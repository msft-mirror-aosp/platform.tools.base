Resources for test that confirms that an ELF with a 16KB PT_LOAD section then a 4KB PT_LOAD section
is correctly interpreted as having a minimum PT_LOAD section of 4KB.

Recreate the .so with:
  ./create-16kb-4kb.sh

See create-16kb-4kb.out for the result of running that script. The PT_LOAD sections are:
  LOAD           0x0000000000004000 0x0000000000000000 0x0000000000000000
                 0x00000000000003e8 0x00000000000003e8  R E    0x4000
  LOAD           0x0000000000005000 0x0000000000001000 0x0000000000001000
                 0x00000000000001d0 0x00000000000001d1  RW     0x1000
