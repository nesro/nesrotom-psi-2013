/**
 *
 *            _           _     _                  _
 *  _ __ ___ | |__   ___ | |_  | | ____ _ _ __ ___| |
 * | '__/ _ \| '_ \ / _ \| __| | |/ / _` | '__/ _ \ |
 * | | | (_) | |_) | (_) | |_  |   < (_| | | |  __/ |
 * |_|  \___/|_.__/ \___/ \__| |_|\_\__,_|_|  \___|_|
 *  _   _                      _                  _ _ _   _
 * | |_| |__   ___   _   _  __| |_ __     ___  __| (_) |_(_) ___  _ __
 * | __| '_ \ / _ \ | | | |/ _` | '_ \   / _ \/ _` | | __| |/ _ \| '_ \
 * | |_| | | |  __/ | |_| | (_| | |_) | |  __/ (_| | | |_| | (_) | | | |
 *  \__|_| |_|\___|  \__,_|\__,_| .__/   \___|\__,_|_|\__|_|\___/|_| |_|
 *                              |_|
 *
 *
 * @author Tomas Nesrovnal
 * @email nesrotom@fit.cvut.cz
 *
 * Tento kod je psany v C99!
 *
 * gcc -Wall -pedantic --std=c99 -ggdb -omain main.c
 * valgrind --leak-check=full --show-reachable=yes ./main
 */

#include <netdb.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/time.h>

/******************************************************************************/

#define LOG 1
#define DEBUG 1

#define HEADER_SIZE 9
#define MAX_DATA_SIZE 255

#define ACK_OVERFLOW 65536

/* how many attempts will be when reaching connId */
#define SYN_ATTEMPTS 50

#define FLAG_SYN 4
#define FLAG_FIN 2
#define FLAG_RST 1
#define FLAG_NUL 0

#define PORT 4000

#define COMMAND_DOWNLOAD 1
#define COMMAND_UPLOAD 2

#define TIMEOUT 100000 /* usec */

#define WINDOW_SIZE 8

/* How many FIN packets fill be send at the end of the up/download. */
#define FIN_PACKETS 5

#define MAX_PACKET_SIZE (HEADER_SIZE + MAX_DATA_SIZE)

/******************************************************************************/

enum EAction {
	DOWNLOAD, UPLOAD,
};

//#pragma pack(1)
struct TPacket {
	uint32_t m_ConnId;
	uint16_t m_SeqNum;
	uint16_t m_AckNum;
	uint8_t m_Flags;
	uint8_t m_Data[255];
	uint8_t m_DataLength;
} TPacket;

struct TConnection {
	int m_SockFD;
	struct sockaddr_in m_RemoteAddr;
	uint32_t m_ConnId;
} TConnection;

struct TWindow {
	struct TPacket *m_PacketStruct[WINDOW_SIZE];
	int m_IsFullPacket[WINDOW_SIZE];
	int m_Position;
} TWindow;

/******************************************************************************/
/* global variables */

struct TConnection g_Connection = { 0, { 0 }, 0 };
struct TPacket g_SynPacket = { 0, 0, 0, FLAG_SYN, { 0 }, 0 };
struct TPacket g_RstPacket = { 0, 0, 0, FLAG_RST, { 0 }, 0 };
struct TPacket g_AckPacket = { 0, 0, 0, FLAG_NUL, { 0 }, 0 };
struct TPacket g_FinPacket = { 0, 0, 0, FLAG_FIN, { 0 }, 0 };
struct TPacket g_RecvPacket = { 0, 0, 0, 0, { 0 }, 0 };
struct TPacket *g_pTmpPacket = NULL;
int i, j;

/******************************************************************************/

void packet_set(struct TPacket *packet, uint32_t connId, uint16_t seqNum,
	uint16_t ackNum, uint8_t flags, uint8_t data[], uint8_t dataLength) {

	packet->m_ConnId = connId;
	packet->m_SeqNum = seqNum;
	packet->m_AckNum = ackNum;
	packet->m_Flags = flags;

	memcpy(packet->m_Data, data, dataLength);

	packet->m_DataLength = dataLength;
}

void packet_set_data(struct TPacket *packet, uint8_t data[], uint8_t dataLength) {
	memcpy(packet->m_Data, data, dataLength);
	packet->m_DataLength = dataLength;
}

void packet_print(const struct TPacket *packet) {
	printf("conn=%08x   seq=%05d   ack=%05d   flag=", packet->m_ConnId,
		packet->m_SeqNum, packet->m_AckNum);

	switch (packet->m_Flags) {
	case FLAG_SYN:
		printf("SYN");
		break;
	case FLAG_FIN:
		printf("FIN");
		break;
	case FLAG_RST:
		printf("RST");
		break;
	case FLAG_NUL:
		printf("NUL");
		break;
	default:
		break;
	}

	printf("   data(%d)\n", packet->m_DataLength);
}

int packet_send(const struct TPacket *packet) {
	struct TPacket toSend;

	memcpy(&toSend, packet, sizeof(TPacket));

	toSend.m_ConnId = htonl(packet->m_ConnId);
	toSend.m_SeqNum = htons(packet->m_SeqNum);
	toSend.m_AckNum = htons(packet->m_AckNum);

	if (sendto(g_Connection.m_SockFD, (char *) &toSend,
		HEADER_SIZE + toSend.m_DataLength, 0,
		(struct sockaddr*) &g_Connection.m_RemoteAddr,
		sizeof(g_Connection.m_RemoteAddr)) < 0) {
		perror("sendto() failed");
		return 0;
	}

#ifdef LOG
	printf("SEND: ");
	packet_print(packet);
#endif

	return 1;
}

int packet_receive(struct TPacket *packet) {
	unsigned int rem_addr_len = 0;
	ssize_t nbytes;

	if ((nbytes = recvfrom(g_Connection.m_SockFD, packet, MAX_PACKET_SIZE, 0,
		(struct sockaddr*) &g_Connection.m_RemoteAddr, &rem_addr_len)) < 0) {
		return 0;
	}

	packet->m_ConnId = ntohl(packet->m_ConnId);
	packet->m_SeqNum = ntohs(packet->m_SeqNum);
	packet->m_AckNum = ntohs(packet->m_AckNum);
	packet->m_DataLength = nbytes - HEADER_SIZE;

#ifdef LOG
	printf("RECV: ");
	packet_print(packet);
#endif

	return 1;
}
/******************************************************************************/

void baryk_connect(const char *address, enum EAction type) {

	struct timeval tv;
	unsigned int inaddr;
	struct hostent *ph;

	if ((inaddr = inet_addr(address)) != INADDR_NONE ) {
		ph = gethostbyaddr((char*) &inaddr, sizeof(unsigned int), AF_INET);
	} else {
		ph = gethostbyname(address);
	}

	if (!ph) {
		perror("Unable to get host address");
		exit(EXIT_FAILURE);
	}

	bzero(&g_Connection.m_RemoteAddr, sizeof(g_Connection.m_RemoteAddr));
	g_Connection.m_RemoteAddr.sin_family = AF_INET;
	g_Connection.m_RemoteAddr.sin_port = htons(PORT);

	bcopy(ph->h_addr_list[0],
		(char *) &g_Connection.m_RemoteAddr.sin_addr.s_addr, ph->h_length);

	if ((g_Connection.m_SockFD = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP))
		< 0) {
		perror("Setting up the socket has failed.");
		exit(EXIT_FAILURE);
	}

	tv.tv_sec = 0;
	tv.tv_usec = TIMEOUT;
	if (setsockopt(g_Connection.m_SockFD, SOL_SOCKET, SO_RCVTIMEO, &tv,
		sizeof(tv)) < 0) {
		perror("Setting up the timeout has failed.");
		exit(EXIT_FAILURE);
	}

	/* connect ****************************************************************/

	uint8_t command[1];
	uint32_t connId;

	switch (type) {
	case DOWNLOAD:
		command[0] = COMMAND_DOWNLOAD;
		packet_set_data(&g_SynPacket, &command[0], 1);
		break;
	case UPLOAD:
		command[0] = COMMAND_UPLOAD;
		packet_set_data(&g_SynPacket, &command[0], 1);
		break;
	}

	/**************************************************************************/

	/**************************************************************************/

	/* pokud mi odmitnu syn a hned mi prijde, tak uz ho nehchci */
	uint32_t wrong[30];
	int wrongCount;

	connId = 0;
	wrongCount = 0;

	for (;;) {
		packet_send(&g_SynPacket);

		if (!packet_receive(&g_RecvPacket)) {
#ifdef DEBUG
			printf("DEBUG: The syn packet has lost.\n");
#endif
			continue;
		}

		if (g_RecvPacket.m_Flags == FLAG_SYN) {
			g_Connection.m_ConnId = g_RecvPacket.m_ConnId;
		}

		break;
	}

	for (; connId == 0;) {

		/* pridam connid co chci odmitat */
		wrong[wrongCount] = g_RecvPacket.m_ConnId;
		wrongCount++;

		if (wrongCount >= SYN_ATTEMPTS) {
			printf("FATAL_ERROR: Over %d wrong syn's. "
				"Maybe the internet connection is really bad! "
				"Exiting by the way...\n", SYN_ATTEMPTS);
			exit(EXIT_FAILURE);
		}

		g_RstPacket.m_ConnId = g_RecvPacket.m_ConnId;
		packet_send(&g_RstPacket);

		if (packet_receive(&g_RecvPacket) && g_RecvPacket.m_Flags == FLAG_SYN) {

			j = 1;
			for (i = 0; i < wrongCount; i++) {
				if (wrong[i] == g_RecvPacket.m_ConnId) {
#ifdef DEBUG
					printf("NOTE: This connection has been already refused.\n");
#endif
					j = 0;
					break;
				}
			}

			if (j) {
				g_Connection.m_ConnId = g_RecvPacket.m_ConnId;
				break;
			}
		}

		packet_send(&g_SynPacket);
	}
}

/******************************************************************************/

int download_main(const char *address) {
#ifdef LOG
	printf("Downloading at: %s\n", address);
#endif

	baryk_connect(address, DOWNLOAD);

	/* When I've the ConnId, I set up few things: */
	g_AckPacket.m_ConnId = g_Connection.m_ConnId;

	struct TWindow window = { { 0 }, { 0 }, 0 };
	for (i = 0; i < WINDOW_SIZE; ++i) {
		window.m_PacketStruct[i] = malloc(sizeof(struct TWindow));
	}

	FILE *fp = NULL;

	fp = fopen("recv_file", "wb");

	if (fp == NULL ) {
		printf("I couldn't open the file for writing.\n");
		exit(EXIT_FAILURE);
	}

	/******************************************************************************************/
	for (;;) {
		if (!packet_receive(&g_RecvPacket)) {
#ifdef DEBUG
			printf("== Packet timeout ==\n");
#endif
		}

		/*		if (packet_broken(recvPacket)) {
		 packet_set(rstPacket, recvPacket->m_ConnId, 0, 0, FLAG_RST, NULL,
		 0);
		 packet_send(connection, rstPacket);

		 if (recvPacket->m_ConnId != connection->m_ConnId) {
		 packet_delete(rstPacket);
		 packet_delete(recvPacket);
		 window_delete(window);

		 perror("Actual connection has been reseted. Ending.\n");
		 return 0;
		 }
		 }*/

		if (g_RecvPacket.m_Flags == FLAG_RST
			&& g_RecvPacket.m_ConnId == g_Connection.m_ConnId) {

#ifdef DEBUG
			printf("== Reset has came. Shutting down. ==\n");
#endif

			break;
		}

		if (g_RecvPacket.m_ConnId != 0
			&& g_RecvPacket.m_ConnId != g_Connection.m_ConnId) {
#ifdef DEBUG
			printf("== Not mine connId, sending RST (pci=%x cci=%x) ==\n",
				g_RecvPacket.m_ConnId, g_Connection.m_ConnId);
#endif

			g_RstPacket.m_ConnId = g_RecvPacket.m_ConnId;
			packet_send(&g_RstPacket);
			continue;
		}

		if (g_RecvPacket.m_Flags == FLAG_FIN) {
			g_FinPacket.m_ConnId = g_Connection.m_ConnId;

			for (i = 0; i < FIN_PACKETS; i++) {
				packet_send(&g_FinPacket);
			}

			break;
		}

		if (g_RecvPacket.m_Flags == FLAG_NUL) {
			/* this is not good solution, but is unbeatable! :D*/
			uint16_t ack = window.m_Position % ACK_OVERFLOW;
			uint8_t isValidSeq = 0;
			uint8_t positionInWindow;

			for (i = 0; i < WINDOW_SIZE; i++) {
				if (((ack + i * 255) % ACK_OVERFLOW) == g_RecvPacket.m_SeqNum) {
					isValidSeq = 1;
					positionInWindow = i;
					break;
				}
			}

			/* invalid seq, sending ack */
			if (isValidSeq == 0) {
				g_AckPacket.m_AckNum = window.m_Position % ACK_OVERFLOW;
				packet_send(&g_AckPacket);
				continue;
			}

			if (window.m_IsFullPacket[positionInWindow] == 0) {
				printf("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZSVG\n");
				sleep(1);
				memcpy(window.m_PacketStruct[positionInWindow], &g_RecvPacket,
					sizeof(struct TPacket));
				window.m_IsFullPacket[positionInWindow] = 1;
			} else {
#ifdef DEBUG
				printf("== I already have this packet. ==\n");
#endif
			}

			/* soupani okynka - dokud na zacatku okynka je nejakej pakcet, tak pisu a soupu */
			while (window.m_IsFullPacket[0]) {

				/* zapis do souboru */
				fwrite(window.m_PacketStruct[0]->m_Data, sizeof(uint8_t),
					window.m_PacketStruct[0]->m_DataLength, fp);

				window.m_Position += window.m_PacketStruct[0]->m_DataLength;

				g_pTmpPacket = window.m_PacketStruct[0];

				/* zasoupnu pole*/
				for (i = 1; i < WINDOW_SIZE; i++) {
					window.m_PacketStruct[i - 1] = window.m_PacketStruct[i];
					window.m_IsFullPacket[i - 1] = window.m_IsFullPacket[i];
				}

				window.m_PacketStruct[WINDOW_SIZE - 1] = g_pTmpPacket;
				window.m_IsFullPacket[WINDOW_SIZE - 1] = 0;
			}
		}

		g_AckPacket.m_AckNum = window.m_Position % ACK_OVERFLOW;
		packet_send(&g_AckPacket);
	}

	for (i = 0; i < WINDOW_SIZE; ++i) {
		free(window.m_PacketStruct[i]);
	}

	fclose(fp);

	/******************************************************************************************/

	return 1;
}

int upload_main(const char *address) {
	baryk_connect(address, UPLOAD);
	return 1;
}

/******************************************************************************/

int main(int argc, char *argv[]) {

	if (argc == 2 && !strcmp(argv[1], "test_download")) {
		printf("== starting: test_download ==\n");

		if (download_main("baryk.fit.cvut.cz")) {
			return EXIT_SUCCESS;
		} else {
			return EXIT_FAILURE;
		}
	}

	/*
	 if (argc == 2 && !strcmp(argv[1], "test_download")) {
	 printf("== starting: test_download ==\n");

	 if (client_main("baryk.fit.cvut.cz")) {
	 return EXIT_SUCCESS;
	 } else {
	 return EXIT_FAILURE;
	 }
	 }

	 if (argc == 2 && !strcmp(argv[1], "test_upload")) {
	 printf("== starting: test_upload ==\n");

	 if (server_main("baryk.fit.cvut.cz", "firmware-karel-1.6.0.bin")) {
	 return EXIT_SUCCESS;
	 } else {
	 return EXIT_FAILURE;
	 }
	 }

	 if (argc == 2) {
	 if (client_main(argv[1])) {
	 return EXIT_SUCCESS;
	 } else {
	 return EXIT_FAILURE;
	 }
	 }

	 if (argc == 3) {
	 if (server_main(argv[1], argv[2])) {
	 return EXIT_SUCCESS;
	 } else {
	 return EXIT_FAILURE;
	 }
	 }
	 */
	printf("usage: ./robot <server> <firmware.bin>\n");
	return EXIT_SUCCESS;
}
