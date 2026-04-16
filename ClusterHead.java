import com.virtenio.radio.ieee_802_15_4.Frame;

import java.util.HashMap;
import java.util.Map;

import com.virtenio.driver.device.at86rf231.AT86RF231;
import com.virtenio.driver.device.at86rf231.AT86RF231RadioDriver;
import com.virtenio.driver.led.LED;
import com.virtenio.misc.StringUtils;
import com.virtenio.radio.ieee_802_15_4.FrameIO;
import com.virtenio.radio.ieee_802_15_4.RadioDriver;
import com.virtenio.radio.ieee_802_15_4.RadioDriverFrameIO;
import com.virtenio.vm.Time;
import com.virtenio.preon32.examples.common.RadioInit;
import com.virtenio.preon32.shuttle.Shuttle;

public class ClusterHead {
	// INI KODE UNTUK CH 1
	// COM4

	private static final int COMMON_PANID = 0xCAFE;
	private static final int COMMON_CHANNEL = 24;

	private static int choiceCH = 5;
	private static long PARENT_NODE = 0;
	private static int CH_ADDR = 0;
	private static long[] CM_ADDR = {};
	
	private static Map<Long, Long> rttTABLE = new HashMap<>();
	private static int countRTT = 0;

	private static volatile boolean exit = false;

	private static AT86RF231 radio;
	private static FrameIO fio;
	private static Shuttle shuttle;
	private static LED green;

	private static void initAll() throws Exception {
		initAddr();
		initRadio();
		initFrameIO();
		initLED();
	}
	
	// ================= ADDRESS =================
	private static void initAddr() throws Exception{
		switch (choiceCH) {
			case 1:
				CH_ADDR = 0xBBBB;
				PARENT_NODE = 0xAAAA;
				CM_ADDR = new long[] {0xBBB1, 0xBBB2, 0xBBB3};
				break;
			case 2:
				CH_ADDR = 0xCCCC;
				PARENT_NODE = 0xAAAA;
				CM_ADDR = new long[] {0xCCC1, 0xCCC2};
				break;
			case 3:
				CH_ADDR = 0xDDDD;
				PARENT_NODE = 0xAAAA;
				CM_ADDR = new long[] {0xDDD1, 0xDDD2};
				break;
			case 4:
				CH_ADDR = 0xBBB3;
				PARENT_NODE = 0xBBBB;
				CM_ADDR = new long[] {0xEEE1, 0xEEE2};
				break;
			case 5:
				CH_ADDR = 0xEEE2;
				PARENT_NODE = 0xBBB3;
				CM_ADDR = new long[] {};
				break;
		}
	}

	// ================= RADIO =================
	private static void initRadio() throws Exception {
		radio = RadioInit.initRadio();

		radio.setChannel(COMMON_CHANNEL);
		radio.setPANId(COMMON_PANID);
		radio.setShortAddress(CH_ADDR);
	}

	// ================= FRAME IO =================
	private static void initFrameIO() throws Exception {
		RadioDriver radioDriver = new AT86RF231RadioDriver(radio);
		fio = new RadioDriverFrameIO(radioDriver);
	}

	private static void initLED() throws Exception {
		shuttle = Shuttle.getInstance();

		green = shuttle.getLED(Shuttle.LED_GREEN);
		green.open();
	}

	// ================= CHECK CM =================
	private static boolean isCM(long addr) {
		for (long cm : CM_ADDR) {
			if (cm == addr) {
				return true;
			}
		}
		return false;
	}

	// ================= RECEIVE =================
	private static void receiveFrame() {
		new Thread() {
			public void run() {
				Frame frame = new Frame();
				while (!exit) {
					try {
						radio.setState(AT86RF231.STATE_RX_AACK_ON);
						fio.receive(frame);

						long tReceived = Time.currentTimeMillis();

						long src = frame.getSrcAddr();
						System.out.println("Received Frame dari: " + src);
						System.out.println("Isi Frame: " + new String(frame.getPayload()));

						if (isCM(src)) {
							processFrameFromCM(frame, tReceived);
						} else if (src == PARENT_NODE) {
							// System.out.println("1. proses frame dari sink");
							processFrameFromSink(frame, tReceived);
						}
						// processFrame(frame, src, timeFrameReceived);
					} catch (Exception e) {

					}
				}
			}
		}.start();
	}

	// ================= PROCESS =================
	private static void processFrameFromCM(Frame frame, long t4) throws Exception {
		String msg = new String(frame.getPayload()).trim();
		String[] parts = StringUtils.split(msg, " ");

		long srcAddress = Long.parseLong(parts[1]);
		System.out.println(srcAddress);

		if (isCM(srcAddress)) {
			System.out.println("masuk sini ga?");
			long srcCM = frame.getSrcAddr();
			// String hex_addr = Long.toHexString(srcCM);

			String reply;

			// int hi = 0; //berapa kali hitung RTT CM

			if (parts[0].equals("1")) {
				// hi++;

				long t1 = Long.parseLong(parts[2]);
				long t2 = Long.parseLong(parts[3]);
				long t3 = Long.parseLong(parts[4]);

				System.out.println("T1:" + t1 + " T2:" + t2 + " T3:" + t3 + " T4:" + t4);

				long RTT = t4 - t1 - (t3 - t2);
				System.out.println(RTT);

				if (!rttTABLE.containsKey(srcCM)) {
					rttTABLE.put(srcCM, RTT);
				} else {
					rttTABLE.put(srcCM, rttTABLE.get(srcCM) + RTT);
				}

				reply = "1 " + srcCM + " " + t3 + " " + RTT;

				sendToSink(fio, reply);
			} else if (parts[0].equals("2")) {
				long t3 = Long.parseLong(parts[3]);
				reply = "2 " + srcCM + " " + t3;
				sendToSink(fio, reply);
			} else if (parts[0].equals("3")) {
				long t1 = Long.parseLong(parts[2]);
				long t2 = Long.parseLong(parts[3]);
				long t3 = Long.parseLong(parts[4]);
				reply = "3 " + srcCM + " " + t1 + " " + t2 + " " + t3 + " " + t4;
				sendToSink(fio, reply);
			} else if (parts[0].equals("SENSE")) {
				sendToSink(fio, msg);
			}
		} else { // kalau bukan dari cluster member --> multihop , CM dari CH 2
			sendToSink(fio, msg);
		}

	}

	private static void processFrameFromSink(Frame frame, long t2) throws Exception {
		byte[] payload = frame.getPayload();

		String msg = new String(payload).trim();

		int code = -1;

		// DEBUG
		// System.out.println("Payload" + msg);

		String[] parts = StringUtils.split(msg, " ");

		if (parts[0].equals("1"))
			code = 1;
		else if (parts[0].equals("2"))
			code = 2;
		else if (parts[0].equals("3"))
			code = 3;
		else if (parts[0].equals("4"))
			code = 4;
		else if (parts[0].equals("5"))
			code = 5;
		else if (parts[0].equals("6"))
			code = 6;
		else if (parts[0].equals("ACK"))
			code = 7;

		switch (code) {
			case 1:
				// Kirim ke seluruh cluster member dalam cluster untuk mencari nilai RTT
				countRTT = countRTT + 1;
				processHELLO(parts, t2);

				for (long cm_addr : CM_ADDR) {
					long t0 = Time.currentTimeMillis();
					msg = "1 " + t0;
					sendToCM(fio, cm_addr, msg);
					Thread.sleep(50);
				}
				break;
			case 2:
				// System.out.println("2. proses synch");
				processSetTimeNOW(parts, t2);

				for (long cm_addr : CM_ADDR) {
					if (!rttTABLE.containsKey(cm_addr)) {
						System.out.println("RTT CM belum ada: " + cm_addr);
						continue;
					}

					long totalRTT = rttTABLE.get(cm_addr);

					long avgRTT = totalRTT / countRTT;

					String message = "2 " + cm_addr + " " + (avgRTT / 2);

					// System.out.println("kirim ke " + cm_addr + " RTT= " + (avgRTT/2));

					sendToCM(fio, cm_addr, message);
				}
				break;
			case 3:
				processGetTimeNOW(parts, t2);

				for (long cm_addr : CM_ADDR) {
					long t0 = Time.currentTimeMillis();
					msg = "3 " + t0;
					sendToCM(fio, cm_addr, msg);
					Thread.sleep(50);
				}
				break;
			case 4:
				for (long cm_addr : CM_ADDR) {
					sendToCM(fio, cm_addr, msg);
					Thread.sleep(50);
				}
				break;
			case 5:

				break;
			case 6:
				exit = true;
				green.off();
				break;
			case 7:
				int cm_addr = Integer.parseInt(parts[1]);
				sendToCM(fio, cm_addr, msg);
				break;
			default:
				break;
		}

		// if (parts[0].equals("ACK") || parts[0].equals("2")) {
		// //System.out.println("2. Depannya 010");
		// //int cm_addr = Integer.parseInt(parts[1]);
		// int cm_addr = Integer.parseInt(parts[1]);
		// //System.out.println("3. Alamat CM: " + cm_addr);
		// forwardToCM(frame, cm_addr);
		// Thread.sleep(50);
		// }
		// if (parts[0].equals("6")) {
		// for(long cm : CM_ADDR){
		// forwardToCM(frame, cm);
		// Thread.sleep(50);
		// }
		// Thread.sleep(200);
		// exit = true;
		// green.off();
		// }
		// else { //pakai unicast
		// for(long cm : CM_ADDR){
		// forwardToCM(frame, cm);
		// Thread.sleep(50);
		// }
		// }
	}

	private static void sendToSink(final FrameIO fio, final String mesg) {
		new Thread() {
			@Override
			public void run() {
				boolean isOK = false;
				while (!isOK) {
					try {
						int frameControl = Frame.TYPE_DATA |
						// Frame.ACK_REQUEST |
								Frame.DST_ADDR_16 |
								Frame.INTRA_PAN |
								Frame.SRC_ADDR_16;

						final Frame frame = new Frame(frameControl);

						frame.setSrcAddr(CH_ADDR);
						frame.setSrcPanId(COMMON_PANID);

						frame.setDestAddr(PARENT_NODE);
						frame.setDestPanId(COMMON_PANID);

						String message = mesg + " " + Time.currentTimeMillis(); // + t3 transmit Time;
						// System.out.println("7. " + message);
						frame.setPayload(message.getBytes());

						radio.setState(AT86RF231.STATE_TX_ARET_ON);
						fio.transmit(frame);
						// System.out.println("4. transmitted " + message);
						isOK = true;
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}.start();
	}

	private static void sendToCM(final FrameIO fio, long cm_addr, final String mesg) {
		new Thread() {
			@Override
			public void run() {
				boolean isOK = false;
				while (!isOK) {
					try {
						int frameControl = Frame.TYPE_DATA |
						// Frame.ACK_REQUEST |
								Frame.DST_ADDR_16 |
								Frame.INTRA_PAN |
								Frame.SRC_ADDR_16;

						final Frame frame = new Frame(frameControl);

						frame.setSrcAddr(CH_ADDR);
						frame.setSrcPanId(COMMON_PANID);

						frame.setDestAddr(cm_addr);
						frame.setDestPanId(COMMON_PANID);

						String message = mesg + " " + Time.currentTimeMillis(); // + t1 transmit Time;
						// System.out.println("7. " + message);
						frame.setPayload(message.getBytes());

						radio.setState(AT86RF231.STATE_TX_ARET_ON);
						fio.transmit(frame);
						// System.out.println("transmitted to CM");
						isOK = true;
					} catch (Exception e) {
						e.printStackTrace();
					}
				}

			}
		}.start();
	}

	private static void processHELLO(final String mesgSplit[], final long t2) throws Exception {
		// after frame received, this node process it and transmit frame for reply
		long t1 = Long.parseLong(mesgSplit[2]); // waktu kirim frame dari sink node
		String message = "1 " + CH_ADDR + " " + t1 + " " + t2;
		// System.out.println("3. " + message);
		sendToSink(fio, message);
	}

	private static void processSetTimeNOW(String mesgSplit[], long t2) throws Exception {
		// format pesan: 010 deltaDelay t1
		// set Time
		Time.setCurrentTimeMillis(
				Long.parseLong(mesgSplit[3]) +
						Long.parseLong(mesgSplit[2]) +
						(Time.currentTimeMillis() - t2));

		// format pesan reply : 010 t2 t3 (time after set)
		String message = "2 " + CH_ADDR + " " + (Time.currentTimeMillis());
		// System.out.println("3. " +
		// stringFormatTime.SFFull(Time.currentTimeMillis()));
		sendToSink(fio, message);
	}

	private static void processGetTimeNOW(String mesgSplit[], long t2) throws Exception {
		// untuk memproses Get time NOW
		// <KodePesan>space<Pesan1>space<Pesan2> = 01 t1 t1
		// 01 t2 t1
		long t1 = Long.parseLong(mesgSplit[2]);
		String message = "3 " + CH_ADDR + " " + t1 + " " + t2;
		sendToSink(fio, message);
	}

	// ================= MAIN =================
	public static void main(String[] args) throws Exception {
		initAll();

		green.on();

		System.out.println("CLUSTER HEAD READY " + Long.toHexString(CH_ADDR));

		receiveFrame();
	}
}