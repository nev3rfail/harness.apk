// Reports what an Android app process can reach on the network.
//
// A runtime that carries its own resolver cannot use Android's, which answers
// through netd rather than a nameserver in /etc/resolv.conf. Pointing it at a
// public resolver only helps if the app is allowed to talk to one directly, so
// that is measured separately from whether the platform resolver works.
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static void tcp_connect(const char *label, const char *ip, int port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) { printf("%-28s socket: %s\n", label, strerror(errno)); return; }
    struct timeval tv = { .tv_sec = 8 };
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    struct sockaddr_in sa = { .sin_family = AF_INET, .sin_port = htons(port) };
    inet_pton(AF_INET, ip, &sa.sin_addr);
    printf("%-28s %s\n", label,
           connect(fd, (struct sockaddr *)&sa, sizeof sa) == 0
               ? "connected" : strerror(errno));
    close(fd);
}

// One A-record query for example.com, sent straight at a resolver.
static void udp_dns(const char *label, const char *ip) {
    static const unsigned char query[] = {
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        7, 'e','x','a','m','p','l','e', 3, 'c','o','m', 0, 0x00, 0x01, 0x00, 0x01
    };
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) { printf("%-28s socket: %s\n", label, strerror(errno)); return; }
    struct timeval tv = { .tv_sec = 5 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    struct sockaddr_in sa = { .sin_family = AF_INET, .sin_port = htons(53) };
    inet_pton(AF_INET, ip, &sa.sin_addr);
    if (sendto(fd, query, sizeof query, 0, (struct sockaddr *)&sa, sizeof sa) < 0) {
        printf("%-28s sendto: %s\n", label, strerror(errno));
        close(fd);
        return;
    }
    unsigned char reply[512];
    ssize_t n = recv(fd, reply, sizeof reply, 0);
    printf("%-28s %s\n", label,
           n > 0 ? "answered" : strerror(errno));
    close(fd);
}

int main(void) {
    struct addrinfo hints = { .ai_family = AF_INET, .ai_socktype = SOCK_STREAM };
    struct addrinfo *res = NULL;
    int rc = getaddrinfo("api.anthropic.com", "443", &hints, &res);
    if (rc != 0) {
        printf("%-28s %s\n", "platform resolver", gai_strerror(rc));
    } else {
        char ip[INET_ADDRSTRLEN] = "?";
        inet_ntop(AF_INET,
                  &((struct sockaddr_in *)res->ai_addr)->sin_addr,
                  ip, sizeof ip);
        printf("%-28s api.anthropic.com -> %s\n", "platform resolver", ip);
        tcp_connect("connect to that address", ip, 443);
        freeaddrinfo(res);
    }

    udp_dns("udp 53 to 8.8.8.8", "8.8.8.8");
    udp_dns("udp 53 to 10.0.2.3", "10.0.2.3");  // the emulator's own resolver
    tcp_connect("tcp 443 to 1.1.1.1", "1.1.1.1", 443);
    return 0;
}
