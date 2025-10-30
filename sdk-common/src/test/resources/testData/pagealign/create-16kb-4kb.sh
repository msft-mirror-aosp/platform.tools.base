#!/bin/bash
#
# This script creates a 64-bit shared object (libtest.so) with
# two PT_LOAD segments:
#   1. The first (for .text) is 16KB aligned.
#   2. The second (for .data) is 4KB aligned.
#
# It uses ':text_seg' (colon) not '>:text_seg' (greater-than).

# --- 1. Check for required tools ---
echo "--- Checking for required tools ---"
if ! command -v clang &> /dev/null; then
    echo "Error: 'clang' not found. Please install clang."
    exit 1
fi

if ! command -v readelf &> /dev/null; then
    echo "Error: 'readelf' not found. Please install binutils."
    exit 1
fi
echo "All tools found."

# --- 2. Create a dummy C source file ---
echo "--- Generating test.c ---"
cat << 'EOF' > test.c
#include <stdio.h>

// This data will go into the 4KB-aligned segment
const char* my_string = "Hello, 4KB-aligned data!";

// This code will go into the 16KB-aligned segment
void my_func() {
    if (my_string) {
        // Just some code to ensure the .text section isn't empty
    }
}
EOF

echo "--- Contents of test.c: ---"
cat test.c
echo "---------------------------"


# --- 3. Create the custom linker script (linker.ld) ---
echo "--- Generating linker.ld (with the :text_seg FIX) ---"
cat << 'EOF' > linker.ld
PHDRS
{
  text_seg PT_LOAD ;  /* 16KB aligned segment */
  data_seg PT_LOAD ;  /* 4KB aligned segment */
}

SECTIONS
{
  /*
   * 16KB aligned segment (code)
   * We assign it to the 'text_seg' PHDR using ':text_seg' (COLON)
   */
  .text : ALIGN(0x4000) {
    *(.init)
    *(.text*)
    *(.fini)
  } :text_seg

  /*
   * 4KB aligned segment (data)
   * We assign it to the 'data_seg' PHDR using ':data_seg' (COLON)
   */
  .data : ALIGN(0x1000) {
    *(.data*)
    *(.rodata*)
    *(.init_array)
    *(.fini_array)
    *(.dynamic)
    *(.got)
    *(.got.plt)
  } :data_seg
}
EOF

echo "--- Contents of linker.ld: ---"
cat linker.ld
echo "----------------------------"

# --- 4. Compile the C file to a 64-bit object file ---
echo "--- Compiling test.c (with -v for verbose output) ---"
clang -v -c -fPIC test.c -o test.o -target x86_64-linux-gnu

# --- 5. Link the object file into a shared object ---
echo "--- Linking libtest.so (with -v for verbose output) ---"
clang -v -shared -Wl,-T,linker.ld test.o -o lib-16kb-4kb.so -target x86_64-linux-gnu

echo "--- 6. Success! libtest.so created. ---"
echo ""

# --- 7. Verify the program headers and their alignments ---
echo "--- Verifying alignments with 'readelf -l' ---"
# Grep for LOAD and the line *after* (A 1) to see the alignment value
readelf -l lib-16kb-4kb.so | grep "LOAD" -A 1

# --- 8. Clean up ---
echo ""
echo "--- Cleaning up intermediate files ---"
rm test.c linker.ld test.o

echo "--- Script finished successfully ---"
