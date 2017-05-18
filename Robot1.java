/**
 * Now, this is a working code!
 * 
 * The first semestral work for the BI-PSI.
 * Based on the: http://baryk.fit.cvut.cz/uloha_1.zip
 * Another sources:
 * - http://tutorials.jenkov.com/java-regex/matcher.html
 * - http://www.vogella.com/articles/JavaRegularExpressions/article.html
 * - https://edux.fit.cvut.cz/courses/BI-PJV/_media/lectures/07/pjv07sit.pdf
 *
 * My port: 3220
 * 
 * @date *.2.2013
 * @version 1.0
 * @author Tomas Nesrovnal (nesrotom@fit.cvut.cz)
 */

package robot;

import java.net.*;
import java.io.*;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When a long message has been read.
 * 
 * @author n
 * 
 */
class LongMessageException extends Exception {
	private static final long serialVersionUID = 6653246816330968567L;
}

/**
 * When an end of stream has been read.
 * 
 * @author n
 * 
 */
class EndOfStreamException extends Exception {
	private static final long serialVersionUID = -431747654910438574L;
}

/**
 * When a robot has stepped out of the town.
 * 
 * @author n
 * 
 */
class OutOfTheTownException extends Exception {
	private static final long serialVersionUID = -9052627935191392109L;
}

/**
 * Helper functions for a client or a server.
 * 
 * @author n
 * 
 */
class NetUtils {

	protected PrintWriter out;
	protected BufferedInputStream in;

	/**
	 * Sends message with proper ending.
	 * 
	 * @param message
	 */
	public void sendMessage(String message) {
		out.print(message + "\r\n");
		out.flush();
	}

	/**
	 * Reads message, saves only to the limit, checks for an end of stream.
	 * 
	 * @param limit
	 * @return
	 * @throws IOException
	 * @throws LongMessageException
	 * @throws EndOfStreamException
	 */
	public String readMessage(int limit) throws IOException,
			LongMessageException, EndOfStreamException {
		StringBuilder stringBuilder = new StringBuilder();
		int currCharacter = 0;
		int nextCharacter = 0;

		try {
			for (int i = 0;; i += 1) {
				currCharacter = this.in.read();

				if (currCharacter == -1) {
					throw new EndOfStreamException();
				}

				if (currCharacter == '\r') {
					nextCharacter = this.in.read();

					if (nextCharacter == '\n') {
						return stringBuilder.toString();
					} else {
						if (i < limit) {
							stringBuilder.append((char) currCharacter);
							stringBuilder.append((char) nextCharacter);
						}
					}
				} else {
					if (i < limit) {
						stringBuilder.append((char) currCharacter);
					}
				}
			}
		} catch (IOException e) {
			System.err.println("I/O error in reading message.");
			e.printStackTrace();
			System.exit(1);
		}

		throw new LongMessageException();
	}

	public static int parseCode(String message) {
		return Integer.parseInt(message.split(" ")[0]);
	}

	public static Robot.COMMANDS parseCommand(String message) {
		String[] tokens = message.split(" ");

		if (tokens.length < 2) {
			return Robot.COMMANDS.UNKNOWN;
		}

		switch (tokens[1]) {
		case "KROK":
			return Robot.COMMANDS.STEP;
		case "VLEVO":
			return Robot.COMMANDS.LEFT;
		case "ZVEDNI":
			return Robot.COMMANDS.LIFT;
		case "OPRAVIT":
			return Robot.COMMANDS.REPAIR;
		default:
			return Robot.COMMANDS.UNKNOWN;
		}
	}
}

class Position {
	public static final String UKNOWN = "UKNOWN_POSITION";

	public int x;
	public int y;

	public Position() {
		this.set(Integer.MIN_VALUE, Integer.MIN_VALUE);
	}

	public Position(int x, int y) {
		this.set(x, y);
	}

	public void set(int x, int y) {
		this.x = x;
		this.y = y;
	}

	@Override
	public String toString() {
		if (this.x == Integer.MIN_VALUE && this.y == Integer.MIN_VALUE) {
			return Position.UKNOWN;
		} else {
			return "(" + this.x + "," + this.y + ")";
		}
	}

	@Override
	public boolean equals(Object object) {
		return object instanceof Position && this.x == ((Position) object).x
				&& this.y == ((Position) object).y;
	}
}

abstract class TheRobot {

	public static enum DIRECTIONS {
		DIRECTION_UNKNOWN, DIRECTION_UP, DIRECTION_RIGHT, DIRECTION_DOWN, DIRECTION_LEFT
	}

	public String name;
	public Position position;
	public DIRECTIONS direction;

	public TheRobot() {
		this.position = new Position();
		this.direction = DIRECTIONS.DIRECTION_UNKNOWN;
	}

	public TheRobot(String name) {
		this();
		this.name = name;
	}

	protected void turnLeft() {
		switch (this.direction) {
		case DIRECTION_UP:
			this.direction = DIRECTIONS.DIRECTION_LEFT;
			break;
		case DIRECTION_RIGHT:
			this.direction = DIRECTIONS.DIRECTION_UP;
			break;
		case DIRECTION_DOWN:
			this.direction = DIRECTIONS.DIRECTION_RIGHT;
			break;
		case DIRECTION_LEFT:
			this.direction = DIRECTIONS.DIRECTION_DOWN;
			break;
		case DIRECTION_UNKNOWN:
			System.err.println("ERROR: The robot can not make a turn "
					+ "left if he doesn't know his direction!");
			System.exit(1);
		default:
			System.err.println("FATAL ERROR: The robot's direction has "
					+ "not been set up!");
			System.exit(1);
			break;
		}
	}

	protected void makeStep() throws OutOfTheTownException {
		switch (this.direction) {
		case DIRECTION_UP:
			this.position.y += 1;
			break;
		case DIRECTION_RIGHT:
			this.position.x += 1;
			break;
		case DIRECTION_DOWN:
			this.position.y -= 1;
			break;
		case DIRECTION_LEFT:
			this.position.x -= 1;
			break;
		case DIRECTION_UNKNOWN:
			System.err
					.println("FATAL ERROR: makeStep() with unknown direction.");
			System.exit(1);
			break;
		default:
			break;
		}

		if (this.position.x > Robot.BOUND_X_MAX
				|| this.position.x < Robot.BOUND_X_MIN
				|| this.position.y > Robot.BOUND_Y_MAX
				|| this.position.y < Robot.BOUND_Y_MIN) {
			throw new OutOfTheTownException();
		}
	}
}

class ClientRobot extends TheRobot {
	private static final int NAME_MARGIN_LEFT = "Oslovuj mne ".length();
	private static final int NAME_MARGIN_RIGHT = ".".length();

	/**
	 * Pattern for name search. It has five parts:
	 * 
	 * 1) 'Oslovuj mne' the part before the name
	 * 
	 * 2) '[^\r\n\0.]' name must not start with '\r', '\n' or '.'.
	 * 
	 * 3) '( +([^\r\n\0])+)*' name can consist from more words.
	 * 
	 * 4) '(([^ \r\n\0])+)' name must not end with ' ', '\r', '\n' or '.'.
	 * 
	 * 5) '\\.' is the part after the name.
	 */
	private static final String NAME_PATTERN = "Oslovuj mne [^\r\n\0.]+( +([^\r\n\0])+)*+(([^ \r\n\0])+)\\.";

	public boolean afterCrash;
	public Position prevPosition;

	public String parseName(String message) {
		Matcher matcher = Pattern.compile(NAME_PATTERN).matcher(message);
		String newName = "";

		if (matcher.find()) {
			newName = message.substring(matcher.start() + NAME_MARGIN_LEFT,
					matcher.end() - NAME_MARGIN_RIGHT);
		} else {
			System.err
					.println("No name pattern has been found in the message.");
			System.exit(1);
		}

		return newName;
	}

	public Position parsePosition(String message) {
		String[] tokens = message.split("[ (,)]");
		
		if (tokens.length < 4) {
			System.err.println("Can not parse position from a message.");
			System.exit(1);
		}

		Position newPosition = new Position(
				Integer.parseInt(tokens[tokens.length - 2]),
				Integer.parseInt(tokens[tokens.length - 1]));

		return newPosition;
	}

	public static void test_parseName() {
		boolean parseNameWorks = true;
		ClientRobot robot = new ClientRobot();

		if (!robot.parseName("Oslovuj mne Honzo.").equals("Honzo")) {
			System.err
					.println("ERROR: parseName(\"Oslovuj mne Honzo.\").equals(\"Honzo\") is FALSE");
			parseNameWorks = false;
		}

		if (!robot.parseName("Oslovuj mne .Oslovuj mne Honzo.").equals("Honzo")) {
			System.err
					.println("ERROR: parseName(\"Oslovuj mne .Oslovuj mne Honzo.\").equals(\"Honzo\") is FALSE");
			parseNameWorks = false;
		}

		if (!robot.parseName("Oslovuj mne Jene .Oslovuj mne Honzo.").equals(
				"Honzo")) {
			System.err
					.println("ERROR: parseName(\"Oslovuj mne Jene .Oslovuj mne Honzo.\").equals(\"Honzo\") is FALSE");
			parseNameWorks = false;
		}

		if (parseNameWorks) {
			System.out
					.println("Function 'String parseName(String message)' works well.");
		} else {
			System.err
					.println("Function 'String parseName(String message)' do not works well.");
		}

	}

	public void computeDirection(String message) {
		int prevX = this.position.x;
		int prevY = this.position.y;

		this.position = this.parsePosition(message);

		int currX = this.position.x;
		int currY = this.position.y;

		if (currX != prevX) {
			if (currX > prevX) {
				this.direction = TheRobot.DIRECTIONS.DIRECTION_RIGHT;
			} else {
				this.direction = TheRobot.DIRECTIONS.DIRECTION_LEFT;
			}
		} else if (currY != prevY) {
			if (currY > prevY) {
				this.direction = TheRobot.DIRECTIONS.DIRECTION_UP;
			} else {
				this.direction = TheRobot.DIRECTIONS.DIRECTION_DOWN;
			}
		} else {
			System.err.println("ERROR: Robot has not been moved.");
			System.exit(1);
		}
	}

	public String computeCommand(String message) {
		if (this.parsePosition(message).equals(Robot.POSITION_ORIGIN)) {
			return "ZVEDNI";
		}

		try {
			switch (this.direction) {
			case DIRECTION_UP:
				this.position = this.parsePosition(message);
				if (this.position.y < 0) {
					super.makeStep();
					return "KROK";
				} else {
					super.turnLeft();
					return "VLEVO";
				}
			case DIRECTION_RIGHT:
				this.position = this.parsePosition(message);
				if (this.position.x < 0) {
					super.makeStep();
					return "KROK";
				} else {
					super.turnLeft();
					return "VLEVO";
				}
			case DIRECTION_DOWN:
				this.position = this.parsePosition(message);
				if (this.position.y > 0) {
					super.makeStep();
					return "KROK";
				} else {
					super.turnLeft();
					return "VLEVO";
				}
			case DIRECTION_LEFT:
				this.position = this.parsePosition(message);
				if (this.position.x > 0) {
					super.makeStep();
					return "KROK";
				} else {
					super.turnLeft();
					return "VLEVO";
				}
			case DIRECTION_UNKNOWN:
				if (this.position.toString() == Position.UKNOWN) {
					this.position = this.parsePosition(message);
					return "KROK";
				} else {
					this.computeDirection(message);
					return this.computeCommand(message);
				}

			default:
				break;
			}

		} catch (OutOfTheTownException e) {
			System.err.println("The robot has stepped out of the town.");
			System.exit(1);
		}

		return null;
	}

}

class ServerRobot extends TheRobot {
	public boolean[] isProcessorBroken;

	public int stepsWithoutBreak;

	public ServerRobot() {
		super();

		this.isProcessorBroken = new boolean[10];

		Random random = new Random();

		int min = -17;
		int max = 17;

		this.position.x = random.nextInt(max + 1 - min) + min;
		this.position.y = random.nextInt(max + 1 - min) + min;

		switch (random.nextInt(3)) {
		case 0:
			this.direction = TheRobot.DIRECTIONS.DIRECTION_UP;
			break;
		case 1:
			this.direction = TheRobot.DIRECTIONS.DIRECTION_RIGHT;
			break;
		case 2:
			this.direction = TheRobot.DIRECTIONS.DIRECTION_DOWN;
			break;
		case 3:
			this.direction = TheRobot.DIRECTIONS.DIRECTION_LEFT;
			break;
		default:
			System.err.println("Random has went crazy!");
			System.exit(1);
		}
	}
}

class SalesWoman extends NetUtils implements Runnable {
	private Socket socket;
	private ServerRobot robot;
	private String message;

	public SalesWoman(Socket socket) {
		this.socket = socket;
		this.robot = new ServerRobot();
	}

	@Override
	public void run() {
		
		Random random = new Random();
		
		try {
			try {
				this.out = new PrintWriter(this.socket.getOutputStream(), true);
				this.in = new BufferedInputStream(this.socket.getInputStream());
			} catch (IOException e) {
				System.err.println("Couldn't get I/O.");
				e.printStackTrace();
				System.exit(1);
			}

			this.sendMessage("210 Ahoj, tady robot verze 0.00. Oslovuj mne nesrotom.");

			int redadedMessages = 0;
			boolean stop = false;
			for (; !stop && redadedMessages <= Robot.MAX_MESSAGES; redadedMessages += 1) {
				try {
					this.message = super.readMessage(30);
				} catch (LongMessageException e) {
					super.sendMessage("500 NEZNAMY PRIKAZ");
					continue;
				} catch (EndOfStreamException e) {
					stop = true;
					break;
				}

				switch (super.parseCommand(message)) {
				case STEP:
					this.robot.stepsWithoutBreak += 1;

					boolean isBroken = false;

					for (int it = 0; it < 9; it += 1) {
						if (this.robot.isProcessorBroken[it] == true) {
							super.sendMessage(Robot.RESPONSE_NOT_FIXED_PROCCESOR);
							isBroken = true;
							break;
						}
					}

					if (isBroken) {
						stop = true;
						break;
					}

					if (this.robot.stepsWithoutBreak > 9) {
						this.robot.stepsWithoutBreak = 0;

						int brokenProcessor = random.nextInt(9) + 1;

						// Indexing from 0.
						this.robot.isProcessorBroken[brokenProcessor - 1] = true;
						super.sendMessage(Robot.RESPONSE_BROKEN_PROCCESOR
								+ brokenProcessor);
						break;
					}

					try {
						this.robot.makeStep();
					} catch (OutOfTheTownException e) {
						super.sendMessage(Robot.RESPONSE_OUT_OF_THE_TOWN);
						stop = true;
						break;
					}

					super.sendMessage("240 OK " + this.robot.position);
					break;
				case LEFT:
					this.robot.turnLeft();
					super.sendMessage(Robot.RESPONSE_OK + this.robot.position);
					break;
				case LIFT:
					if (this.robot.position.equals(Robot.POSITION_ORIGIN)) {
						super.sendMessage(Robot.RESPONSE_SUCCESS
								+ Robot.SUCCESS_MESSAGE);
					} else {
						super.sendMessage(Robot.RESPONSE_NOT_ON_THE_MARK);
					}

					stop = true;
					break;
				case REPAIR:
					String[] tokens = this.message.split(" ");

					if (tokens.length != 3) {
						System.err.println("Not a valid repair. Message: "
								+ this.message);
						System.exit(1);
					}

					int processorId = Integer.parseInt(tokens[2]);

					if (processorId < 1 || processorId > 9) {
						super.sendMessage(Robot.RESPONSE_UNKNOWN_COMMAND);
						break;
					}

					// Indexing from 0.
					processorId -= 1;

					if (this.robot.isProcessorBroken[processorId]) {
						this.robot.isProcessorBroken[processorId] = false;
						super.sendMessage("240 OK " + this.robot.position);
					} else {
						super.sendMessage(Robot.RESPONSE_BAD_REPAIR);
						stop = true;
					}

					break;
				case UNKNOWN:
					super.sendMessage(Robot.RESPONSE_UNKNOWN_COMMAND);
					break;
				default:
					break;
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		} finally {
			try {
				if (this.socket != null) {
					this.socket.close();
				}

				this.in.close();
				this.out.close();
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(1);
			}
		}
	}
}

class Server  {
	private final int NUMBER_OF_THREADS = 2;

	private final ServerSocket serverSocket;
	private final ExecutorService pool;

	public Server(int port) throws IOException {
		this.serverSocket = new ServerSocket(port);
		this.pool = Executors.newFixedThreadPool(this.NUMBER_OF_THREADS);

		try {
			for (;;) {
				pool.execute(new SalesWoman(serverSocket.accept()));
			}
		} catch (IOException e) {
			pool.shutdown();
			e.printStackTrace();
		}
	}
}

class Client extends NetUtils {

	private static final String NONE_COMMAND = "NONE_COMMAND";

	private Socket echoSocket;
	private String message;
	private int port;
	private String servername;
	private ClientRobot robot;
	private String lastCommand = NONE_COMMAND;

	public boolean success;

	public Client(int port, String servername) throws IOException {
		this.port = port;
		this.servername = servername;
	}

	public boolean loop() throws IOException {
		int redadedMessages = 0;
		for (; redadedMessages <= Robot.MAX_MESSAGES; redadedMessages += 1) {

			try {
				message = super.readMessage(2_000_000);
			} catch (LongMessageException e) {

			} catch (EndOfStreamException e) {
				e.printStackTrace();
			}

			switch (NetUtils.parseCode(message)) {
			case 210:
				this.robot.name = this.robot.parseName(message);
				super.sendMessage(this.robot.name + " VLEVO");
				break;
			case 240:
				if (this.robot.afterCrash) {
					this.robot.afterCrash = false;
					this.sendCommand(this.lastCommand);
					break;
				}

				this.sendCommand(this.robot.computeCommand(message));
				break;
			case 260:
				System.out.println("Message: " + message);
				return true;
			case 580:
				this.robot.afterCrash = true;
				this.sendMessage(this.robot.name + " OPRAVIT "
						+ this.getProcessorID(message));
				break;
			default:
				System.err.println("ERROR: Unknown code!");
				System.exit(1);
			}
		}

		if (redadedMessages >= Robot.MAX_MESSAGES) {
			System.err.println("Messages has exceeded the limit MAX_MESSAGES.");
			System.exit(1);
		}

		return false;
	}

	public void inicialize() throws IOException, UnknownHostException {
		try {
			this.echoSocket = new Socket(servername, port);
			this.out = new PrintWriter(echoSocket.getOutputStream(), true);
			this.in = new BufferedInputStream(this.echoSocket.getInputStream());
		} catch (UnknownHostException e) {
			System.err.println("Don't know about host: " + servername);
			e.printStackTrace();
			System.exit(1);
		} catch (IOException e) {
			System.err.println("Couldn't get I/O for " + servername);
			e.printStackTrace();
			System.exit(1);
		}

		this.robot = new ClientRobot();
	}

	public void cleanup() throws IOException {
		out.close();
		in.close();
		echoSocket.close();
	}

	private String getProcessorID(String message) {
		String[] tokens = message.split(" ");

		return tokens[tokens.length - 1];
	}

	private void sendCommand(String command) {
		this.lastCommand = command;
		String message = this.robot.name + " " + command;

		out.print(message + "\r\n");
		out.flush();
	}
}

public class Robot {

	public static final String SUCCESS_MESSAGE = "Rainbow pony is the best one!";

	public final static String RESPONSE_WELCOME = "210 ";
	public final static String RESPONSE_OK = "240 OK ";
	public final static String RESPONSE_SUCCESS = "260 USPECH ";
	public final static String RESPONSE_UNKNOWN_COMMAND = "500 NEZNAMY PRIKAZ";
	public final static String RESPONSE_OUT_OF_THE_TOWN = "530 HAVARIE";
	public final static String RESPONSE_NOT_ON_THE_MARK = "550 NELZE ZVEDNOUT ZNACKU";
	public final static String RESPONSE_BAD_REPAIR = "571 PROCESOR FUNGUJE";
	public final static String RESPONSE_NOT_FIXED_PROCCESOR = "572 ROBOT SE ROZPADL";
	public final static String RESPONSE_BROKEN_PROCCESOR = "580 SELHANI PROCESORU ";
	public final static int CODE_NAME = 210;
	public final static int CODE_OK = 240;
	public final static int CODE_SUCCESS = 260;

	public static enum COMMANDS {
		STEP, LEFT, LIFT, REPAIR, UNKNOWN
	}

	public final static int LOCALHOST_PORT = 3999;
	public final static String LOCALHOST_SERVER = "localhost";
	public final static String BARYK_SERVER = "baryk.fit.cvut.cz";
	public final static int NESROTOM_PORT = 3220;

	public final static int TEST_CLIENT_COUNT = 5;

	public final static Position POSITION_ORIGIN = new Position(0, 0);
	public final static Position POSITION_UNKNOWN = new Position();

	public final static int BOUND_X_MAX = 18;
	public final static int BOUND_X_MIN = -18;
	public final static int BOUND_Y_MAX = 18;
	public final static int BOUND_Y_MIN = -18;

	public static final int MAX_MESSAGE_LENGHT = 1_000_000_000;
	public static final int MAX_MESSAGES = 1_000;

	public static void main(String[] args) throws IOException {

		if (args.length == 0) {
			System.err.println("Client: java robot.Robot <hostname> <port>");
			System.err.println("Server: java robot.Robot <port>");
			System.exit(1);
		} else if (args.length == 1) {
			if (args[0].equals("baryk")) {
				int success = 0;
				int fail = 0;
				double beforeTestTime = System.nanoTime();
				double afterTestTime = 0;

				Client client = new Client(Robot.LOCALHOST_PORT,
						Robot.BARYK_SERVER);

				for (int i = 1; i <= Robot.TEST_CLIENT_COUNT; i += 1) {
					System.out.println("Test number: " + i + ".");

					client.inicialize();

					if (client.loop()) {
						success += 1;
					} else {
						fail += 1;
					}

					client.cleanup();
				}

				afterTestTime = System.nanoTime() - beforeTestTime;

				System.out.println("Client test results:\n"
						+ "  number of tests = " + Robot.TEST_CLIENT_COUNT
						+ "\n" + "  success = " + success + "\n  fail = "
						+ fail + "\n  time = " + (afterTestTime * 10e-10d)
						+ " seconds\n" + "  one test in = "
						+ ((afterTestTime / Robot.TEST_CLIENT_COUNT) * 10e-10d)
						+ " seconds\n");

				ClientRobot.test_parseName();
				return;
			} else if (args[0].equals("client")) {
				System.out.println("Testing the client for localhost.\n");

				Client client = new Client(Robot.LOCALHOST_PORT,
						Robot.LOCALHOST_SERVER);

				client.inicialize();
				client.loop();
				client.cleanup();

				return;
			} else if (args[0].equals("server")) {
				System.out.println("Testing the server for localhost.\n");

				Server server = new Server(Robot.LOCALHOST_PORT);
//
//				Thread serverThread = new Thread(server);
//				serverThread.start();

				return;
			} else {
				int port = Integer.parseInt(args[0]);
				System.out.println("jo");
				Server server = new Server(port);
			}
		} else {
			Client client = new Client(Integer.parseInt(args[1]), args[0]);

			client.inicialize();
			client.loop();
			client.cleanup();
		}
	}
}
