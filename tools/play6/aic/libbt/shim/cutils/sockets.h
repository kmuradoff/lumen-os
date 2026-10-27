/*
 * Shim for the local-socket part of AOSP <cutils/sockets.h>.
 * Static implementations matching libcutils socket_local_server.cpp /
 * socket_local_client_unix.cpp (AF_LOCAL, abstract namespace = leading NUL,
 * reserved namespace = /dev/socket/, filesystem namespace = plain path).
 */
#pragma once
#include <errno.h>
#include <stddef.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>

#define ANDROID_SOCKET_NAMESPACE_ABSTRACT   0
#define ANDROID_SOCKET_NAMESPACE_RESERVED   1
#define ANDROID_SOCKET_NAMESPACE_FILESYSTEM 2
#define ANDROID_RESERVED_SOCKET_PREFIX "/dev/socket/"
#define CUTILS_LISTEN_BACKLOG 4

static inline int socket_make_sockaddr_un(const char *name, int namespaceId,
                                          struct sockaddr_un *p_addr, socklen_t *alen)
{
    size_t namelen;
    memset(p_addr, 0, sizeof(*p_addr));
    switch (namespaceId) {
    case ANDROID_SOCKET_NAMESPACE_ABSTRACT:
        namelen = strlen(name);
        if (namelen + 1 > sizeof(p_addr->sun_path))
            return -1;
        p_addr->sun_path[0] = 0;
        memcpy(p_addr->sun_path + 1, name, namelen);
        break;
    case ANDROID_SOCKET_NAMESPACE_RESERVED:
        namelen = strlen(name) + strlen(ANDROID_RESERVED_SOCKET_PREFIX);
        if (namelen > sizeof(p_addr->sun_path) - 1)
            return -1;
        strcpy(p_addr->sun_path, ANDROID_RESERVED_SOCKET_PREFIX);
        strcat(p_addr->sun_path, name);
        break;
    case ANDROID_SOCKET_NAMESPACE_FILESYSTEM:
        namelen = strlen(name);
        if (namelen > sizeof(p_addr->sun_path) - 1)
            return -1;
        strcpy(p_addr->sun_path, name);
        break;
    default:
        return -1;
    }
    p_addr->sun_family = AF_LOCAL;
    *alen = (socklen_t)(namelen + offsetof(struct sockaddr_un, sun_path) + 1);
    return 0;
}

static inline int socket_local_server_bind(int s, const char *name, int namespaceId)
{
    struct sockaddr_un addr;
    socklen_t alen;
    int n = 1;
    if (socket_make_sockaddr_un(name, namespaceId, &addr, &alen) < 0)
        return -1;
    if (namespaceId != ANDROID_SOCKET_NAMESPACE_ABSTRACT)
        unlink(addr.sun_path);
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &n, sizeof(n));
    if (bind(s, (struct sockaddr *)&addr, alen) < 0)
        return -1;
    return s;
}

static inline int socket_local_server(const char *name, int namespaceId, int type)
{
    int s = socket(AF_LOCAL, type, 0);
    if (s < 0)
        return -1;
    if (socket_local_server_bind(s, name, namespaceId) < 0) {
        int saved = errno;
        close(s);
        errno = saved;
        return -1;
    }
    if (type == SOCK_STREAM || type == SOCK_SEQPACKET) {
        if (listen(s, CUTILS_LISTEN_BACKLOG) < 0) {
            int saved = errno;
            close(s);
            errno = saved;
            return -1;
        }
    }
    return s;
}

static inline int socket_local_client_connect(int fd, const char *name, int namespaceId, int type)
{
    struct sockaddr_un addr;
    socklen_t alen;
    (void)type;
    if (socket_make_sockaddr_un(name, namespaceId, &addr, &alen) < 0)
        return -1;
    if (connect(fd, (struct sockaddr *)&addr, alen) < 0)
        return -1;
    return fd;
}

static inline int socket_local_client(const char *name, int namespaceId, int type)
{
    int s = socket(AF_LOCAL, type, 0);
    if (s < 0)
        return -1;
    if (socket_local_client_connect(s, name, namespaceId, type) < 0) {
        close(s);
        return -1;
    }
    return s;
}

static inline int socket_close(int sock) { return close(sock); }
