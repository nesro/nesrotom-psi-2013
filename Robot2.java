/**
 * @file Robot.java
 * @author Tomas Nesrovnal
 * @email nesrotom@fit.cvut.cz
 */

package robot;

/******************************************************************************/

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/******************************************************************************/

class Utils {

	public static class TwoBytes {
		public byte a;
		public byte b;

		public TwoBytes() {
		}

		public TwoBytes(short a) {
			this.a = (byte) (a >> 8);
			this.b = (byte) a;
		}

		public TwoBytes(byte a, byte b) {
			this.a = a;
			this.b = b;
		}
	}

	public static class FourBytes {
		public byte a;
		public byte b;
		public byte c;
		public byte d;

		public FourBytes() {
		}

		public FourBytes(int a) {
			this.a = (byte) (a >> 24);
			this.b = (byte) (a >> 16);
			this.c = (byte) (a >> 8);
			this.d = (byte) a;
		}

		public FourBytes(byte a, byte b, byte c, byte d) {
			this.a = a;
			this.b = b;
			this.c = c;
			this.d = d;
		}
	}

	/**************************************************************************/

	public static byte[] intToFourBytes(int a) {
		byte[] toReturn = new byte[4];

		toReturn[0] = (byte) (a >> 24);
		toReturn[1] = (byte) (a >> 16);
		toReturn[2] = (byte) (a >> 8);
		toReturn[3] = (byte) a;

		return toReturn;
	}

	public static byte[] intToTwoBytes(int a) {
		byte[] toReturn = new byte[2];

		toReturn[0] = (byte) (a >> 8);
		toReturn[1] = (byte) a;

		return toReturn;
	}

	public static int fourBytesToInt(byte a, byte b, byte c, byte d) {
		// @formatter:off
		return	(int) (
					(a << 24) & 0xFF000000 |
					(b << 16) & 0x00FF0000 |
					(c << 8)  & 0x0000FF00 |
					 d        & 0x000000FF
				);
		// @formatter:on
	}

	public static short twoBytesToShort(byte a, byte b) {
		// @formatter:off
		return	(short) (
					(a << 8)  & 0xFF00 |
					 b        & 0x00FF
				);
		// @formatter:on
	}

	/**************************************************************************/

	public static void test() {
		int ok = 0;
		int fail = 0;

		System.out.println("Starting test of Utils.");

		/**********************************************************************/

		int[] testInts = { 0, 1, -1, 666, -2_147_483_648, 2_147_483_647 };

		FourBytes fourBytes = null;

		byte a1 = 0;
		byte b1 = 0;
		byte c1 = 0;
		byte d1 = 0;

		for (int i : testInts) {
			fourBytes = new FourBytes(i);

			a1 = fourBytes.a;
			b1 = fourBytes.b;
			c1 = fourBytes.c;
			d1 = fourBytes.d;

			if (i != fourBytesToInt(a1, b1, c1, d1)) {
				fail += 1;
				System.err.println("int->fourbytes->int, where int=" + i
						+ ", has failed!");
			} else {
				ok += 1;
			}
		}

		/**********************************************************************/

		System.out.println("Test has ended with: ok=" + ok + ",fails=" + fail);

	}
}

/******************************************************************************/

class Packet {

	public final static int HEADER_SIZE = 9;
	public final static int MAX_DATA_LENGTH = 255;

	/**
	 * Flag byte are zero bits.
	 */
	public final static byte FLAG_NONE = 0x00;

	/**
	 * Otevření nového spojení. Posílá klient i server (pouze) na začátku v
	 * prvním paketu. V datové části musí být právě 1 byte s kódem příkazu.
	 */
	public final static byte FLAG_SYN = 0x04;

	/**
	 * Ukončení spojení. Posílá klient i server, pokud již nemají žádná další
	 * data k odeslání. Paket s nastaveným příznakem FIN již nemůže obsahovat
	 * žádná data. Ukončení spojení nelze odvolat. Oba směry spojení se
	 * uzavírají zvlášť. Sekvenční číslo se po odeslání FIN již nesmí zvětšit.
	 */
	public final static byte FLAG_FIN = 0x02;

	/**
	 * Zrušení spojení kvůli chybě. Posílá klient i server v případě detekování
	 * logické chyby v hodnotách v hlavičce. Např. přijatý paket neobsahuje
	 * příznak SYN a ID spojení není evidováno. Nebo je hodnota potvrzovacího
	 * čísla menší, než byla v posledním přijatém paketu (klesá). Pozor na
	 * přetečení sekvenčních a potvrzovacích čísel. Žádná z komunikujících stran
	 * po odeslání paketu s příznakem RST již dále neukončuje spojení
	 * standardním způsobem - spojení je přenosem paketu s příznakem RST
	 * definitivně ukončeno.
	 */
	public final static byte FLAG_RST = 0x01;

	/**************************************************************************/

	public int connId;
	public short seqNum;
	public short ackNum;
	public byte flags;
	public byte data[];
	public long time;

	
	
	/**************************************************************************/

	public Packet(int connId, short seqNum, short ackNum, byte flags,
			byte data[]) {
		this.connId = connId;
		this.seqNum = seqNum;
		this.ackNum = ackNum;
		this.flags = flags;

		if (data == null) {
			this.data = new byte[0];
		} else {
			this.data = data;
		}
	}

	public Packet(byte[] byteArray, int size) {

		/* Covert 4x byte to int */
		// @formatter:off
		this.connId = (byteArray[0] << 24) & 0xFF000000 |
				      (byteArray[1] << 16) & 0x00FF0000 |
				      (byteArray[2] << 8)  & 0x0000FF00 |
				       byteArray[3]        & 0x000000FF;
		// @formatter:on

		/* Covert 2x byte to short */
		this.seqNum = (short) ((byteArray[4] << 8) & 0xFF00 | byteArray[5] & 0x00FF);

		/* Covert 2x byte to short */
		this.ackNum = (short) ((byteArray[6] << 8) & 0xFF00 | byteArray[7] & 0x00FF);

		this.flags = byteArray[8];

		this.data = new byte[size - Packet.HEADER_SIZE];
		System.arraycopy(byteArray, Packet.HEADER_SIZE, this.data, 0, size
				- Packet.HEADER_SIZE);
	}

	/**************************************************************************/

	public byte[] getAsByteArray() {
		byte[] toReturn = new byte[Packet.HEADER_SIZE + data.length];

		/* Convert int to 4x byte. */
		toReturn[0] = (byte) (this.connId >> 24);
		toReturn[1] = (byte) (this.connId >> 16);
		toReturn[2] = (byte) (this.connId >> 8);
		toReturn[3] = (byte) this.connId;

		/* Convert short to 2x byte. */
		toReturn[4] = (byte) (this.seqNum >> 8);
		toReturn[5] = (byte) this.seqNum;

		/* Convert short to 2x byte. */
		toReturn[6] = (byte) (this.ackNum >> 8);
		toReturn[7] = (byte) this.ackNum;

		toReturn[8] = (byte) this.flags;

		// FIXME test this, print is in hexa
		System.arraycopy(this.data, 0, toReturn, Packet.HEADER_SIZE,
				this.data.length);

		if (Robot.DEBUG && false) {
			System.out.println("asByteArray: ");
			for (byte _byte : toReturn) {
				System.out.print(String.format("%02X ", _byte));
			}

			System.out.println("");
		}
		return toReturn;
	}

	public int getTotalSize() {
		return Packet.HEADER_SIZE + this.data.length;
	}

	public String toString() {
		StringBuilder sb = new StringBuilder();

		sb.append("connId=" + Integer.toHexString(this.connId));

		sb.append(",seqNum=" + this.seqNum);

		sb.append(",ackNum=" + this.ackNum);

		sb.append(",flags=");
		switch (this.flags) {
		case Packet.FLAG_FIN:
			sb.append("FIN");
			break;
		case Packet.FLAG_RST:
			sb.append("RST");
			break;
		case Packet.FLAG_SYN:
			sb.append("SYN");
			break;
		case Packet.FLAG_NONE:
			sb.append("NONE");
			break;
		default:
			System.err.println("Unknown flag. Exiting.");
			System.exit(1);
		}

		if (this.data == null) {
			sb.append(",data=null");
		} else {
			sb.append(",data_len=" + this.data.length);
		}

		// sb.append(",data='");

		// for (byte _byte : this.data) {
		// sb.append(String.format("%02X ", _byte));
		// }

		// if (this.data.length > 2) {
		// sb.append(String.format("%02X ", this.data[0]));
		// sb.append(" .. ");
		// sb.append(String.format("%02X ", this.data[this.data.length - 1]));
		// }
		//
		// sb.append("'");

		return sb.toString();
	}

	public boolean isSyn() {
		if (this.flags == Packet.FLAG_SYN) {
			return true;
		}

		return false;
	}

	public boolean isData() {
		if (this.flags == Packet.FLAG_NONE && this.data != null
				&& this.data.length > 0) {
			return true;
		}

		return false;
	}

	public boolean isFin() {
		if (this.flags == Packet.FLAG_FIN) {
			return true;
		}

		return false;
	}

}

/******************************************************************************/

class UDP {
	/**
	 * 
	 */
	public final static int TIMEOUT = 100;

	public int port;
	public InetAddress address;
	public String addressName;
	public DatagramSocket socket;

	public UDP(String serverAddress, int port) {
		try {
			socket = new DatagramSocket();
		} catch (SocketException e) {
			System.err.println("Creation of DatagramSocket failed.");
			e.printStackTrace();
			System.exit(1);
		}

		try {
			socket.setSoTimeout(UDP.TIMEOUT);
		} catch (SocketException e) {
			e.printStackTrace();
		}

		try {
			address = InetAddress.getByName(serverAddress);
		} catch (UnknownHostException e) {
			System.err.println("Unknown address of the server.");
			e.printStackTrace();
			System.exit(1);
		}

		this.port = port;

		this.socket.connect(this.address, this.port);
	}

	public void send(Packet packet) {

		System.out.println("sending packet: " + packet);

		// System.out.println("asbytearray:" + packet.getAsByteArray());

		// TODO:
		// packet.time = System.currentTimeMillis();

		if (Robot.DEBUG) {
			/* IMHO this would be a pain in the ass */
			if (packet.getAsByteArray().length != packet.getTotalSize()) {
				System.err
						.println("FATAL ERROR: packet.getAsByteArray().length "
								+ "!= packet.getTotalSize()");
				System.exit(1);
			}
		}

		DatagramPacket datagramPacket = new DatagramPacket(
				packet.getAsByteArray(), packet.getTotalSize(), this.address,
				this.port);

		try {
			socket.send(datagramPacket);
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}

	public Packet recv() throws SocketTimeoutException {
		byte[] byteArray = new byte[Packet.HEADER_SIZE + Packet.MAX_DATA_LENGTH
				+ 1];

		DatagramPacket datagramPacket = new DatagramPacket(byteArray,
				Packet.HEADER_SIZE + Packet.MAX_DATA_LENGTH);

		try {
			this.socket.receive(datagramPacket);
		} catch (SocketTimeoutException e) {
			throw e;
		} catch (IOException e) {
			System.out.println("System receive failed.");
			e.printStackTrace();
			System.exit(1);
		}

		return new Packet(datagramPacket.getData(), datagramPacket.getLength());
	}
}

/******************************************************************************/

class Download {

	/**************************************************************************/

	DatagramSocket socket;
	DatagramPacket packet;
	InetAddress address;
	InetAddress fromAddress;
	String messageString;
	byte[] message;

	/**
	 * FIXME: is this right? (EDUX java template) From which port message was
	 * received?
	 */
	int fromPort;

	private UDP udp;

	/**
	 * This holds the file.
	 */
	// private List<Byte> data;

	/**
	 * Connection identifier.
	 */
	private int connId = -1;

	/**
	 * posledni ack kde mam vsechny data v pohode
	 */
	private int lastAckInRow = 0;

	/**
	 * budu si pamatovat posledni ack, abych mohl urcit, jestli preteklo
	 */
	private int prevAck = -1;

	/**
	 * kolikrat uz mi acekacko preteklo?
	 */
	private int ackOwerflowCount = 0;

	/**
	 * Mapa pole bytu. Predpokladam, ze kazdej zaznam bude dlouhej 255 bytu a
	 * ten posledni bude min
	 */
	Map<Integer, byte[]> buffer;

	List<Byte> data;

	Map<Integer, Byte[]> allData;

	public Download(String serverName) {
		this.buffer = new HashMap<Integer, byte[]>();
		this.data = new ArrayList<Byte>();

		/* Create UDP wrapper */
		this.udp = new UDP(serverName, Robot.PORT);

		/**********************************************************************/

		System.out.println("]]] I'll connect to the server:");

		/* Connect to server. */
		this.connectToServer();

		/**********************************************************************/

		System.out.println("]]] Ok. I'm connected. connId="
				+ Integer.toHexString(this.connId));

		Packet recvPacket = null;
		for (int _ = 0;; _++) {
			System.out.println("loop: " + _);

			/* don't hurry, be happy (: */
			// try {
			// Thread.sleep(100);
			// } catch (InterruptedException e1) {
			// }

			try {
				recvPacket = this.udp.recv();
			} catch (SocketTimeoutException e) {
				System.out.println("timeout==================================");
				continue;
			}

			System.out.println("RECV: " + recvPacket);

			/* If the connId is different to mine, send a RST packet. */
			if (recvPacket.connId != this.connId) {
				System.out.println("wrong connId, sengind rst");
				this.udp.send(new Packet(recvPacket.connId, (short) 0,
						(short) 0, Packet.FLAG_RST, null));
				continue;
			}

			if (recvPacket.isFin()) {
				System.out.println("FIN was received. Sending 20x RST.");

				for (int i = 0; i < 20; i += 1) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException e1) {
					}

					this.udp.send(new Packet(this.connId, (short) 0,
							(short) (this.data.size()), Packet.FLAG_FIN, null));
				}

				break;
			}

			/******************************************************************/

			if (recvPacket.isData()) {
				System.out.println("a data packet has came!");

				// TODO: owerflow
				/*
				 * int dataPosition = recvPacket.seqNum + this.ackOwerflowCount
				 * * 65536;
				 */
				if (recvPacket.ackNum < this.lastAckInRow) {
					System.out.println("tohle uz ale mam!");
				} else if (recvPacket.seqNum == this.data.size()) {
					System.out.println("presne tohle jsem potreboval");

					/* mam co chci, tak to tam nacpu */
					for (Byte b : recvPacket.data) {
						this.data.add(b);
					}

					while (this.buffer.containsKey(this.data.size())) {
						int size = this.data.size();
						System.out.println("mam dalsi data o chci! " + size);

						for (byte b : this.buffer.get(size)) {
							System.out.println("pridavam: " + b);
							this.data.add(b);
						}

						this.buffer.remove(size);
					}

				} else {

					System.out.println("to jeste nechci, tak si to ulozim. ds="
							+ this.data.size() + ",seq=" + recvPacket.seqNum);
					this.prevAck = recvPacket.seqNum;

					byte[] tmpData = new byte[recvPacket.data.length];

					System.arraycopy(recvPacket.data, 0, tmpData, 0,
							recvPacket.data.length);

					System.out.println("toto jsou tmp data co ukladam: ");
					for (byte b : tmpData) {
						System.out.print(b + " ");
					}
					System.out.println(".");

					this.buffer.put((int) recvPacket.seqNum, tmpData);

				}

			}

			this.udp.send(new Packet(this.connId, (short) 0, (short) (this.data
					.size()), Packet.FLAG_NONE, null));

		}// konec for

		System.out.println("tak jsem venku ze smycky");

		writeItToFile();

		System.out.println("thiii end :)");

	}

	private void writeItToFile() {
		FileWriter fstream = null;

		try {
			fstream = new FileWriter("recv.txt");
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}

		BufferedWriter out = new BufferedWriter(fstream);

		/* neslo by to rychlejc? */
		for (int i = 0; i < this.data.size(); i += 1) {
			try {
				Byte b = this.data.get(i);
				System.out.println(i + ", pisu: " + b);

				if (b == null) {
					continue;
				}

				out.write(b);
				out.flush();
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(1);
			}
		}

		try {
			out.close();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	/**
	 * 
	 * @return
	 */
	public void connectToServer() {
		Packet recvPacket = null;

		this.udp.send(new Packet(0, (short) 0, (short) 0, Packet.FLAG_SYN,
				new byte[] { Robot.SIGN_DOWNLOAD }));

		try {
			recvPacket = this.udp.recv();
			System.out.println("__recv: " + recvPacket);
		} catch (SocketTimeoutException e) {
			System.out.println("SYN timeout, connecting again..");
			this.connectToServer();
			return;
		}

		if (recvPacket == null) {
			System.err.println("Received packet is null.");
			System.exit(1);
		}

		if (recvPacket.isSyn()) {
			this.connId = recvPacket.connId;
			return;
		}

		/* nebudem to komplikovat :) */
		System.exit(1);

		/* neprisel syn, prectu zbytek a uvidi se... */
		for (;;) {
			try {
				recvPacket = this.udp.recv();
				System.out.println("recv: " + recvPacket);
			} catch (SocketTimeoutException e1) {
			}

			if (recvPacket.isSyn()) {
				this.connId = recvPacket.connId;
				return;
			}

			this.udp.send(new Packet(recvPacket.connId, (short) 0, (short) 0,
					Packet.FLAG_RST, null));

			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
			}

			this.udp.send(new Packet(0, (short) 0, (short) 0, Packet.FLAG_SYN,
					new byte[] { Robot.SIGN_DOWNLOAD }));

		}
	}
}

/******************************************************************************/

public class Robot {

	public static final boolean DEBUG = true;
	public static final boolean LOCALHOST = true;

	public static final byte SIGN_DOWNLOAD = 0x01;
	public static final byte SIGN_UPLOAD = 0x02;

	public static final int PORT_BARYK = 3220;
	public static final int PORT_LOCALHOST = 4000;

	public static int PORT = -1;

	/**************************************************************************/

	public static void main(String[] args) {

		/* set up right port */
		if (Robot.LOCALHOST) {
			Robot.PORT = Robot.PORT_LOCALHOST;
		} else {
			Robot.PORT = Robot.PORT_BARYK;
		}

		/* tests */
		Utils.test();

		System.out.println("...");

		/* ... */
		if (args.length == 1) {
			System.out.println("Starting: DOWNLOAD");
			@SuppressWarnings("unused")
			Download d = new Download(args[0]);
		} else if (args.length == 2) {
			System.out.println("Starting: UPLOAD");
			System.out.println("//todo: do it");
		} else {
			System.out.println("Usage: java robot.Robot <server> [firmware]");
		}
	}
}
