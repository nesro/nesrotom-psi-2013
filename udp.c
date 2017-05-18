/**
 * @author Tomas Nesrovnal
 * @email nesrotom@fit.cvut.cz
 *
 * gcc -Wall -ggdb -o main main.c
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
#include <unistd.h>
#include <arpa/inet.h>

/******************************************************************************/

#define LOG 1
#define DEBUG 1

#define HEADER_SIZE 9
#define MAX_DATA_SIZE 255

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
#define FIN_PACKETS 20

#define MAX_PACKET_SIZE (HEADER_SIZE + MAX_DATA_SIZE)

#define MAX_UPLOAD_SIZE 100000
#define MAX_UPLOAD_DATAGRAMS 392

/******************************************************************************/

typedef struct TPacket {
	uint32_t m_ConnId;
	uint16_t m_SeqNum;
	uint16_t m_AckNum;
	uint8_t m_Flags;
	uint8_t m_Data[255];
	uint8_t m_DataLength;
} TPacket;

typedef struct TConnection {
	int m_SockFD;
	struct sockaddr_in m_RemoteAddr;
	uint32_t m_ConnId;
} TConnection;

/******************************************************************************/

int g_SocketFD;
struct sockaddr_in g_RemoteAddr;
char g_Buffer[MAX_PACKET_SIZE];
int i, j;

/******************************************************************************/

void packet_reset(TPacket *packet) {
	bzero(packet, sizeof(TPacket));
}

TPacket * packet_new() {
	TPacket *packet;
	packet = calloc(1, sizeof(TPacket));

	if (packet == NULL ) {
		perror("calloc failed\n");
	}

	return packet;
}

void packet_delete(TPacket *packet) {
	free(packet);
}

int packet_size(TPacket *packet) {
	return HEADER_SIZE + packet->m_DataLength;
}

int packet_broken(TPacket *packet) {
	/* TODO: */
	return 0;
}

void packet_set(TPacket *packet, uint32_t connId, uint16_t seqNum,
	uint16_t ackNum, uint8_t flags, uint8_t data[], uint8_t dataLength) {

	packet->m_ConnId = connId;
	packet->m_SeqNum = seqNum;
	packet->m_AckNum = ackNum;
	packet->m_Flags = flags;

	memcpy(packet->m_Data, data, dataLength);

	packet->m_DataLength = dataLength;
}

void packet_print(const TPacket *packet) {
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

int packet_send(TConnection *connection, const TPacket *packet) {
	TPacket *toSend;

	toSend = packet_new();
	memcpy(toSend, packet, sizeof(TPacket));

	toSend->m_ConnId = htonl(packet->m_ConnId);
	toSend->m_SeqNum = htons(packet->m_SeqNum);
	toSend->m_AckNum = htons(packet->m_AckNum);

	if (sendto(connection->m_SockFD, (char *) toSend, packet_size(toSend), 0,
		(struct sockaddr*) &connection->m_RemoteAddr,
		sizeof(connection->m_RemoteAddr)) < 0) {
		perror("sendto() failed");
		free(toSend);
		return 0;
	}

	if (LOG) {
		printf("SEND: ");
		packet_print(packet);
	}

	free(toSend);
	return 1;
}

int packet_receive(TConnection *connection, TPacket *packet) {
	unsigned int rem_addr_len = 0;
	ssize_t nbytes;

	if ((nbytes = recvfrom(connection->m_SockFD, g_Buffer, MAX_PACKET_SIZE, 0,
		(struct sockaddr*) &connection->m_RemoteAddr, &rem_addr_len)) < 0) {
		return 0;
	}

	memcpy(packet, g_Buffer, nbytes);
	packet->m_ConnId = ntohl(packet->m_ConnId);
	packet->m_SeqNum = ntohs(packet->m_SeqNum);
	packet->m_AckNum = ntohs(packet->m_AckNum);
	packet->m_DataLength = nbytes - HEADER_SIZE;

	if (LOG) {
		printf("RECV: ");
		packet_print(packet);
	}

	return 1;
}

/******************************************************************************/
/* window functions */

typedef struct TWindow {
	TPacket *m_PacketStruct[WINDOW_SIZE];
	int m_IsFullPacket[WINDOW_SIZE];
	int m_Position;
} TWindow;

TWindow * window_new() {
	TWindow *window;

	window = calloc(1, sizeof(TWindow));

	for (i = 0; i < WINDOW_SIZE; i++) {
		window->m_PacketStruct[i] = packet_new();
	}

	if (window == NULL ) {
		perror("calloc failed\n");
	}

	return window;
}

void window_delete(TWindow *window) {
	for (i = 0; i < WINDOW_SIZE; i++) {
		packet_delete(window->m_PacketStruct[i]);
	}

	free(window);
}

void window_print(TWindow *window) {
	printf("WINDOW: start=%d", window->m_Position);

	for (i = 0; i < WINDOW_SIZE; i++) {
		printf(" [%d/%d]=%d", i, i * 255 + window->m_Position,
			window->m_IsFullPacket[i]);
	}

	printf("\n");
}

/******************************************************************************/
/* connection and karel */

TConnection * connection_new_client(char *address) {
	TConnection *connection;
	struct timeval tv;

	unsigned int inaddr;
	struct hostent* ph;

	if ((inaddr = inet_addr(address)) != INADDR_NONE ) {
		ph = gethostbyaddr((char*) &inaddr, sizeof(unsigned int), AF_INET);
	} else {
		ph = gethostbyname(address);
	}

	if (!ph) {
		perror("Unable to get host address");
		exit(EXIT_FAILURE);
	}

	connection = malloc(sizeof(TConnection));

	bzero(&g_RemoteAddr, sizeof(g_RemoteAddr));
	connection->m_RemoteAddr.sin_family = AF_INET;
	connection->m_RemoteAddr.sin_port = htons(PORT);

	bcopy(ph->h_addr, (char*) &connection->m_RemoteAddr.sin_addr.s_addr, ph->h_length);

	if ((connection->m_SockFD = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)) < 0) {
		perror("Setting up the socket has failed.");
		exit(EXIT_FAILURE);
	}

	tv.tv_sec = 0;
	tv.tv_usec = TIMEOUT;
	if (setsockopt(connection->m_SockFD, SOL_SOCKET, SO_RCVTIMEO, &tv,
		sizeof(tv)) < 0) {
		perror("Setting up the timeout has failed.");
		exit(EXIT_FAILURE);
	}

	return connection;
}

void connection_delete(TConnection *connection) {
	free(connection);
}

void connection_send_ack(TConnection *connection, TWindow *window,
	TPacket *ackPacket) {

	if (DEBUG) {
		printf("Total ACK=%d\n", window->m_Position);
	}

	packet_set(ackPacket, connection->m_ConnId, 0, window->m_Position % 65536,
		FLAG_NUL, NULL, 0);
	packet_send(connection, ackPacket);

}

/******************************************************************************/

uint32_t karel_connect(TConnection *connection, int type) {
	uint8_t commandDownload[] = { COMMAND_DOWNLOAD };
	uint8_t commandUpload[] = { COMMAND_UPLOAD };
	uint32_t connId;

	TPacket *synPacket;
	TPacket *rstPacket;
	TPacket *recvPacket;

	/**************************************************************************/

	/* pokud mi odmitnu syn a hned mi prijde, tak uz ho nehchci */
	uint32_t wrong[30];
	int wrongCount;

	connId = 0;
	wrongCount = 0;

	synPacket = packet_new();
	rstPacket = packet_new();
	recvPacket = packet_new();

	if (type == 0) {
		packet_set(synPacket, 0, 0, 0, FLAG_SYN, commandDownload, 1);
	} else if (type == 1) {
		packet_set(synPacket, 0, 0, 0, FLAG_SYN, commandUpload, 1);
	}

	for (;;) {
		packet_send(connection, synPacket);

		if (!packet_receive(connection, recvPacket)) {
			if (DEBUG) {
				printf("The syn packet has lost.\n");
			}
			continue;
		}

		if (recvPacket->m_Flags == FLAG_SYN) {
			connId = recvPacket->m_ConnId;
		}

		break;
	}

	for (; connId == 0;) {

		/* pridam connid co chci odmitat */
		wrong[wrongCount] = recvPacket->m_ConnId;
		wrongCount++;

		if (wrongCount >= 30) {
			printf("over 30 wrong syn's? Something is wrong. exit.");
			exit(EXIT_FAILURE);
		}

		packet_set(rstPacket, recvPacket->m_ConnId, 0, 0, FLAG_RST, NULL, 0);
		packet_send(connection, rstPacket);

		if (packet_receive(connection,
			recvPacket) && recvPacket->m_Flags == FLAG_SYN) {

			j = 1;
			for (i = 0; i < wrongCount; i++) {
				if (wrong[i] == recvPacket->m_ConnId) {
					if (DEBUG) {
						printf(
							"NOTE: This connection has been already refused.\n");
					}
					j = 0;
					break;
				}
			}

			if (j) {
				connId = recvPacket->m_ConnId;
				break;
			}
		}

		packet_send(connection, synPacket);
	}

	packet_delete(synPacket);
	packet_delete(rstPacket);
	packet_delete(recvPacket);

	return connId;
}

int karel_download_loop(TConnection *connection) {
	TPacket *ackPacket;
	TPacket *rstPacket;
	TPacket *finPacket;
	TPacket *recvPacket;
	TPacket *tmpPacket;

	int err = 0;

	TWindow *window;

	FILE *fp;

	/**************************************************************************/

	ackPacket = packet_new();
	rstPacket = packet_new();
	finPacket = packet_new();
	recvPacket = packet_new();

	window = window_new();

	fp = fopen("recv_file", "wb");

	if (fp == NULL ) {
		printf("I couldn't open the file for writing.\n");
		exit(0);
	}

	/**************************************************************************/

	for (;;) {
		if (!packet_receive(connection, recvPacket)) {
			if (DEBUG) {
				printf("== Packet timeout ==\n");
			}
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

		if (recvPacket->m_Flags == FLAG_RST
			&& recvPacket->m_ConnId == connection->m_ConnId) {
			if (DEBUG) {
				printf("== Reset has came. Shutting down. ==\n");
			}
			err = 1;
			break;
		}

		if (recvPacket->m_ConnId != 0
			&& recvPacket->m_ConnId != connection->m_ConnId) {
			if (DEBUG) {
				printf("== Not mine connId, sending RST (pci=%x cci=%x) ==\n",
					recvPacket->m_ConnId, connection->m_ConnId);
			}

			packet_set(rstPacket, recvPacket->m_ConnId, 0, 0, FLAG_RST, NULL,
				0);
			packet_send(connection, rstPacket);
			continue;
		}

		if (recvPacket->m_Flags == FLAG_FIN) {
			packet_set(finPacket, connection->m_ConnId, 0, 0, FLAG_FIN, NULL,
				0);

			for (i = 0; i < FIN_PACKETS; i++) {
				packet_send(connection, finPacket);
			}

			break;
		}

		if (recvPacket->m_Flags == FLAG_NUL) {
			uint16_t ack = window->m_Position % 65536;
			uint8_t isValidSeq = 0;
			uint8_t positionInWindow;

			for (i = 0; i < WINDOW_SIZE; i++) {
				if (((ack + i * 255) % 65536) == recvPacket->m_SeqNum) {
					isValidSeq = 1;
					positionInWindow = i;
					break;
				}
			}

			/* invalid seq, sending ack */
			if (isValidSeq == 0) {
				connection_send_ack(connection, window, ackPacket);
				continue;
			}

			if (window->m_IsFullPacket[positionInWindow] == 0) {
				memcpy(window->m_PacketStruct[positionInWindow], recvPacket,
					sizeof(TPacket));
				window->m_IsFullPacket[positionInWindow] = 1;
			} else {
				if (DEBUG) {
					printf("== I already have this packet. ==\n");
				}
			}

			/* soupani okynka - dokud na zacatku okynka je nejakej pakcet, tak pisu a soupu */
			while (window->m_IsFullPacket[0]) {

				/* zapis do souboru */
				fwrite(window->m_PacketStruct[0]->m_Data, sizeof(uint8_t),
					window->m_PacketStruct[0]->m_DataLength, fp);

				/*
				 if (window->m_PacketStruct[0]->m_DataLength < 255) {
				 printf("KONEC SOUBORU last_len=%d\n",
				 window->m_PacketStruct[0]->m_DataLength);
				 }*/

				window->m_Position += window->m_PacketStruct[0]->m_DataLength;

				tmpPacket = window->m_PacketStruct[0];

				/* zasoupnu pole*/
				for (i = 1; i < WINDOW_SIZE; i++) {
					window->m_PacketStruct[i - 1] = window->m_PacketStruct[i];
					window->m_IsFullPacket[i - 1] = window->m_IsFullPacket[i];
				}

				window->m_PacketStruct[WINDOW_SIZE - 1] = tmpPacket;
				window->m_IsFullPacket[WINDOW_SIZE - 1] = 0;

			}

		}

		connection_send_ack(connection, window, ackPacket);
	}

	/**************************************************************************/

	packet_delete(ackPacket);
	packet_delete(rstPacket);
	packet_delete(finPacket);
	packet_delete(recvPacket);
	window_delete(window);

	fclose(fp);

	if (err) {
		return 0;
	} else {
		return 1;
	}
}

void karel_download(char *address) {
	TConnection *connection;

	connection = connection_new_client(address);
	connection->m_ConnId = karel_connect(connection, 0);

	printf("Connection ID is %x.\n", connection->m_ConnId);

	if (karel_download_loop(connection)) {
		printf("Download has successfully ended.\n");
	} else {
		printf("Download has failed.\n");
	}

	connection_delete(connection);
}

/******************************************************************************/
/* UPLOAAAAAAAAAAAAAAAAAAD */

int karel_upload(char *address, char *binpath) {
	TConnection *connection;

	connection = connection_new_client(address);
	connection->m_ConnId = karel_connect(connection, 1);

	printf("Connection ID is %x.\n", connection->m_ConnId);

	/* upload loop ************************************************************/

	/* * prepare part *********************************************************/

	FILE *fp = NULL;
	size_t readed;

	/*
	 * $ wc -c firmware-karel-1.6.0.bin
	 * 99135 firmware-karel-1.6.0.bin
	 */
	unsigned int lastDatagram = 0;
	unsigned int lastDatagramSize = 0;
	unsigned int recvACK = 0;
	uint16_t lastAck = 33599;
	uint8_t data[MAX_UPLOAD_DATAGRAMS][255];
	uint16_t seq;

	fp = fopen(binpath, "rb");

	if (fp == NULL ) {
		printf("Path of the firmvare has not been found.\n");
		exit(EXIT_FAILURE);
	}

	for (i = 0;; ++i) {
		readed = fread(&data[i], 1, 255, fp);

		if (readed != 255) {
			lastDatagramSize = readed;
			lastDatagram = i;
			break;
		}
	}

	TPacket *tmpPacket;
	TPacket *recvPacket;
	tmpPacket = packet_new();
	recvPacket = packet_new();

	/* * main part ************************************************************/

	for (;;) {
		for (i = 0; i < WINDOW_SIZE; ++i) {
			seq = (i + (recvACK / 255)) * 255;

			if (i + (recvACK / 255) == lastDatagram) {
				printf("Sending the last datagram!\n");

				packet_set(tmpPacket, connection->m_ConnId, seq, 0, FLAG_NUL,
					data[i + (recvACK / 255)], lastDatagramSize);
				packet_send(connection, tmpPacket);

				break;
			}

			packet_set(tmpPacket, connection->m_ConnId, seq, 0, FLAG_NUL,
				data[i + (recvACK / 255)], 255);
			if (!packet_send(connection, tmpPacket)) {
				--i;
				continue;
			}
		}

		if (!packet_receive(connection, recvPacket)) {
			continue;
		}

		if (recvPacket->m_Flags == FLAG_RST) {
			printf("FAIL: RST has been send! Restarting the proccess.\n");
			sleep(1);

			fclose(fp);
			packet_delete(tmpPacket);
			packet_delete(recvPacket);
			connection_delete(connection);

			return 1;
		}

		if (recvPacket->m_AckNum == lastAck) {
			printf("FIN!\n");
			packet_set(tmpPacket, connection->m_ConnId, 33599, 0, FLAG_FIN,
				NULL, 0);

			for (i = 0; i < FIN_PACKETS; i++) {
				packet_send(connection, tmpPacket);
			}

			break;
		}

		for (i = 0; i < WINDOW_SIZE * 5; ++i) {
			//printf("is: %d?\n", (recvACK + i * 255) % 65536);
			if (recvPacket->m_AckNum == (recvACK + i * 255) % 65536) {
				recvACK += i * 255;
				break;
			}
		}

		printf("recvACK = %d\n", recvACK);
	}
	/* * cleanup part *********************************************************/

	/* end of upload loop *****************************************************/

	printf(
		"GO CHECK: http://baryk.fit.cvut.cz/cgi-bin/robotudp?akce=log&connid=%X\n",
		connection->m_ConnId);

	fclose(fp);
	packet_delete(tmpPacket);
	packet_delete(recvPacket);
	connection_delete(connection);

	return 0;
}

int main(int argc, char *argv[]) {
	if (argc == 2) {
		karel_download(argv[1]);
		return EXIT_SUCCESS;
	}

	if (argc == 3) {
		while (karel_upload(argv[1], argv[2]))
			;
		return EXIT_SUCCESS;
	}

	printf("usage: ./main <adresa> [soubor]\n");

	return EXIT_SUCCESS;
}
