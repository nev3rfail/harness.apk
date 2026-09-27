// Edits one field of a 64-bit little-endian ELF in place, to make test fixtures:
//
//   elfedit <file> tls-align <n>   sets PT_TLS p_align
//   elfedit <file> machine <n>     sets e_machine
//   elfedit <file> no-sections     zeroes e_shoff, e_shnum and e_shstrndx
#include <elf.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: elfedit <file> <field> [value]\n"); return 2; }
    int fd = open(argv[1], O_RDWR);
    Elf64_Ehdr eh;
    if (fd < 0 || pread(fd, &eh, sizeof eh, 0) != sizeof eh) { perror(argv[1]); return 1; }
    if (strcmp(argv[2], "machine") == 0 && argc == 4) {
        eh.e_machine = (Elf64_Half)atoi(argv[3]);
    } else if (strcmp(argv[2], "no-sections") == 0) {
        eh.e_shoff = 0;
        eh.e_shnum = 0;
        eh.e_shstrndx = 0;
    } else if (strcmp(argv[2], "tls-align") == 0 && argc == 4) {
        for (int i = 0; i < eh.e_phnum; i++) {
            Elf64_Phdr ph;
            off_t at = (off_t)(eh.e_phoff + (Elf64_Off)i * eh.e_phentsize);
            if (pread(fd, &ph, sizeof ph, at) != sizeof ph) { perror("phdr"); return 1; }
            if (ph.p_type != PT_TLS) continue;
            ph.p_align = strtoull(argv[3], NULL, 0);
            if (pwrite(fd, &ph, sizeof ph, at) != sizeof ph) { perror("phdr"); return 1; }
            return 0;
        }
        fprintf(stderr, "%s: no PT_TLS\n", argv[1]);
        return 1;
    } else {
        fprintf(stderr, "unknown field %s\n", argv[2]);
        return 2;
    }
    if (pwrite(fd, &eh, sizeof eh, 0) != sizeof eh) { perror(argv[1]); return 1; }
    return 0;
}
