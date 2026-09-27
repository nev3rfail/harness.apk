/*
 * Writes the static ELF programs the proot host tests execute, for this
 * machine's architecture:
 *
 *   mkelf good OUT        one PT_LOAD; the program exits 0
 *   mkelf misaligned OUT  one PT_LOAD whose p_vaddr - p_offset is not a
 *                         multiple of the page size
 *   mkelf shared OUT      two PT_LOADs in one page, R+X then R+W
 *   mkelf same OUT        two PT_LOADs in one page, both R+X; exits 0
 *   mkelf class32 OUT     an ELFCLASS32 program
 */
#include <elf.h>
#include <stdio.h>
#include <string.h>

#if defined(__x86_64__)
#define MACHINE EM_X86_64
#define MACHINE32 EM_386
/* mov $60, %eax; xor %edi, %edi; syscall */
static const unsigned char code[] = { 0xb8, 0x3c, 0, 0, 0, 0x31, 0xff, 0x0f, 0x05 };
#elif defined(__aarch64__)
#define MACHINE EM_AARCH64
#define MACHINE32 EM_ARM
/* mov x0, #0; mov x8, #93; svc #0 */
static const unsigned char code[] = { 0x00, 0x00, 0x80, 0xd2, 0xa8, 0x0b, 0x80, 0xd2,
				      0x01, 0x00, 0x00, 0xd4 };
#else
#error "no exit sequence for this architecture"
#endif

#define BASE 0x400000
#define CODE 0x100
#define SECOND 0x800

static unsigned char file[0x1000];

static void load(Elf64_Phdr *ph, Elf64_Off off, Elf64_Addr vaddr, Elf64_Xword size, Elf64_Word flags) {
	ph->p_type = PT_LOAD;
	ph->p_flags = flags;
	ph->p_offset = off;
	ph->p_vaddr = ph->p_paddr = vaddr;
	ph->p_filesz = ph->p_memsz = size;
	ph->p_align = 0x1000;
}

static size_t build64(const char *mode) {
	Elf64_Ehdr *eh = (Elf64_Ehdr *) file;
	Elf64_Phdr *ph = (Elf64_Phdr *) (file + sizeof *eh);
	Elf64_Addr base = BASE;
	size_t size = CODE + sizeof code;

	memcpy(eh->e_ident, ELFMAG, SELFMAG);
	eh->e_ident[EI_CLASS] = ELFCLASS64;
	eh->e_ident[EI_DATA] = ELFDATA2LSB;
	eh->e_ident[EI_VERSION] = EV_CURRENT;
	eh->e_type = ET_EXEC;
	eh->e_machine = MACHINE;
	eh->e_version = EV_CURRENT;
	eh->e_phoff = sizeof *eh;
	eh->e_ehsize = sizeof *eh;
	eh->e_phentsize = sizeof *ph;
	eh->e_phnum = 1;
	memcpy(file + CODE, code, sizeof code);

	if (!strcmp(mode, "misaligned"))
		base += 0x100;
	load(&ph[0], 0, base, size, PF_R | PF_X);
	if (!strcmp(mode, "shared") || !strcmp(mode, "same")) {
		eh->e_phnum = 2;
		load(&ph[1], SECOND, BASE + SECOND, 0x10,
		     !strcmp(mode, "shared") ? PF_R | PF_W : PF_R | PF_X);
		size = SECOND + 0x10;
	}
	eh->e_entry = base + CODE;
	return size;
}

static size_t build32(void) {
	Elf32_Ehdr *eh = (Elf32_Ehdr *) file;
	Elf32_Phdr *ph = (Elf32_Phdr *) (file + sizeof *eh);

	memcpy(eh->e_ident, ELFMAG, SELFMAG);
	eh->e_ident[EI_CLASS] = ELFCLASS32;
	eh->e_ident[EI_DATA] = ELFDATA2LSB;
	eh->e_ident[EI_VERSION] = EV_CURRENT;
	eh->e_type = ET_EXEC;
	eh->e_machine = MACHINE32;
	eh->e_version = EV_CURRENT;
	eh->e_phoff = sizeof *eh;
	eh->e_ehsize = sizeof *eh;
	eh->e_phentsize = sizeof *ph;
	eh->e_phnum = 1;
	eh->e_entry = 0x8048000 + CODE;
	ph->p_type = PT_LOAD;
	ph->p_flags = PF_R | PF_X;
	ph->p_vaddr = ph->p_paddr = 0x8048000;
	ph->p_filesz = ph->p_memsz = CODE + 0x10;
	ph->p_align = 0x1000;
	return CODE + 0x10;
}

int main(int argc, char **argv) {
	size_t size;
	FILE *out;

	if (argc != 3) {
		fprintf(stderr, "usage: mkelf good|misaligned|shared|same|class32 OUT\n");
		return 2;
	}
	size = !strcmp(argv[1], "class32") ? build32() : build64(argv[1]);
	out = fopen(argv[2], "wb");
	if (!out || fwrite(file, 1, size, out) != size || fclose(out)) {
		perror(argv[2]);
		return 1;
	}
	return 0;
}
