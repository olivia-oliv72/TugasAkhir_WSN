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
	

	private static int choiceCH = 4;
	private static long PARENT_NODE = 0;
	private static int CH_ADDR = 0;
	private static long[] CM_ADDR = {};
	
	private static Map<Long, Long> rttTABLE = new HashMap<>();
	private static int countCheckOnline = 0;

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

	private static void initRadio() throws Exception {
		radio = RadioInit.initRadio();

		radio.setChannel(COMMON_CHANNEL);
		radio.setPANId(COMMON_PANID);
		radio.setShortAddress(CH_ADDR);
	}

	private static void initFrameIO() throws Exception {
		RadioDriver radioDriver = new AT86RF231RadioDriver(radio);
		fio = new RadioDriverFrameIO(radioDriver);
	}

	private static void initLED() throws Exception {
		shuttle = Shuttle.getInstance();

		green = shuttle.getLED(Shuttle.LED_GREEN);
		green.open();
	}
	
	private static void threadSleep(int ms) {
	    try {
	        Thread.sleep(ms);
	    } catch (InterruptedException e) {
	        Thread.currentThread().interrupt();
	    }
	}

	private static boolean isCM(long addr) {
		for (long cm : CM_ADDR) {
			if (cm == addr) {
				return true;
			}
		}
		return false;
	}

	private static void receiveFrame() {
		new Thread() {
			@Override
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
							processFrameFromSink(frame, tReceived);
						}
					} catch (Exception e) {
						threadSleep(50);
					}
				}
			}
		}.start();
	}

	private static void processFrameFromSink(Frame frame, long t2) throws Exception {
		byte[] payload = frame.getPayload();
		String message = new String(payload).trim();
		String[] parts = StringUtils.split(message, " ");
		String messageCM;
		
		String code = parts[0];

		switch (code) {
			case "1":
				// Kirim ke seluruh cluster member dalam cluster untuk mencari nilai RTT
				countCheckOnline = countCheckOnline + 1;
				processHELLO(parts, t2);

				for (long cm_addr : CM_ADDR) {
					messageCM = "1";
					sendToCM(fio, cm_addr, messageCM);
					Thread.sleep(50);
				}
				break;
			case "2":
				processSetTimeNOW(parts, t2);

				for (long cm_addr : CM_ADDR) {
					if (!rttTABLE.containsKey(cm_addr)) {
						System.out.println("RTT CM belum ada: " + cm_addr);
						continue;
					}

					long totalRTT = rttTABLE.get(cm_addr);
					long avgRTT = totalRTT / countCheckOnline;
					messageCM = "2 " + cm_addr + " " + (avgRTT / 2);

					sendToCM(fio, cm_addr, messageCM);
					Thread.sleep(50);
				}
				break;
			case "3":
				processGetTimeNOW(parts);

				for (long cm_addr : CM_ADDR) {
					messageCM = "3";
					sendToCM(fio, cm_addr, messageCM);
					Thread.sleep(50);
				}
				break;
			case "4":
				for (long cm_addr : CM_ADDR) {
					sendToCM(fio, cm_addr, message);
//					Thread.sleep(50);
				}
				break;
			case "5":
				for (long cm_addr : CM_ADDR) {
					sendToCM(fio, cm_addr, message);
//					Thread.sleep(50);
				}

				exit = true;
				green.off();
				radio.setState(AT86RF231.STATE_TRX_OFF);
				break;
			case "ACK":
				int cm_addr = Integer.parseInt(parts[1]);
				sendToCM(fio, cm_addr, message);
//				Thread.sleep(50);
				break;
			default:
				break;
		}
	}
	
	private static void processFrameFromCM(Frame frame, long t4) throws Exception {
		String message = new String(frame.getPayload()).trim();
		String[] parts = StringUtils.split(message, " ");

		String code = parts[0];
		long srcAddress = Long.parseLong(parts[1]); // Bisa menangani multihop CH

		if (isCM(srcAddress)) { 
			String reply;
			
			switch(code) {
				case "1":
					long t1 = Long.parseLong(parts[2]);
					long t2 = Long.parseLong(parts[3]);
					long t3 = Long.parseLong(parts[4]);
					long RTT = t4 - t1 - (t3 - t2);

					if (!rttTABLE.containsKey(srcAddress)) {
						rttTABLE.put(srcAddress, RTT);
					} else {
						rttTABLE.put(srcAddress, rttTABLE.get(srcAddress) + RTT);
					}

					reply = "1 " + srcAddress + " " + t3 + " " + RTT + " " + CH_ADDR;
					sendToSink(fio, reply);
					
					break;
				case "2":
					long time = Long.parseLong(parts[2]);
					reply = "2 " + srcAddress + " " + time;
					sendToSink(fio, reply);
//					Thread.sleep(50);
					break;
				case "3":
					time = Long.parseLong(parts[2]);
					reply = "3 " + srcAddress + " " + time;
					sendToSink(fio, reply);
//					Thread.sleep(50);
					break;
				case "4":
					sendToSink(fio, message);
//					Thread.sleep(50);
					break;
			}
		} else { // kalau bukan dari cluster member --> multihop , CM dari CH 2
			sendToSink(fio, message);
		}

	}

	private static void sendToSink(final FrameIO fio, final String message) {
		try {
			int frameControl = Frame.TYPE_DATA |
							Frame.ACK_REQUEST |
							Frame.DST_ADDR_16 |
							Frame.INTRA_PAN |
							Frame.SRC_ADDR_16;

			final Frame frame = new Frame(frameControl);

			frame.setSrcAddr(CH_ADDR);
			frame.setSrcPanId(COMMON_PANID);

			frame.setDestAddr(PARENT_NODE);
			frame.setDestPanId(COMMON_PANID);

			String messagePlusTime = message + " " + Time.currentTimeMillis(); // + t3 transmit Time;
			frame.setPayload(messagePlusTime.getBytes());
			radio.setState(AT86RF231.STATE_TX_ARET_ON);
			fio.transmit(frame);
		} catch (Exception e) {
			threadSleep(50);
		}
	}

	private static void sendToCM(final FrameIO fio, long cm_addr, final String message) {
		try {
			int frameControl = Frame.TYPE_DATA |
							Frame.ACK_REQUEST |
							Frame.DST_ADDR_16 |
							Frame.INTRA_PAN |
							Frame.SRC_ADDR_16;

			final Frame frame = new Frame(frameControl);

			frame.setSrcAddr(CH_ADDR);
			frame.setSrcPanId(COMMON_PANID);

			frame.setDestAddr(cm_addr);
			frame.setDestPanId(COMMON_PANID);

			String messagePlusTime = message + " " + Time.currentTimeMillis(); // + t1 transmit Time;
			frame.setPayload(messagePlusTime.getBytes());

			radio.setState(AT86RF231.STATE_TX_ARET_ON);
			fio.transmit(frame);
		} catch (Exception e) {
			threadSleep(50);
		}
	}

	private static void processHELLO(final String mesgSplit[], final long t2) throws Exception {
		long t1 = Long.parseLong(mesgSplit[1]); // waktu kirim frame dari sink node
		String message = "1 " + CH_ADDR + " " + t1 + " " + t2;
		sendToSink(fio, message);
	}

	private static void processSetTimeNOW(String mesgSplit[], long t2) throws Exception {
		Time.setCurrentTimeMillis(
				Long.parseLong(mesgSplit[3]) +
						Long.parseLong(mesgSplit[2]) +
						(Time.currentTimeMillis() - t2));

		String message = "2 " + CH_ADDR + " " + (Time.currentTimeMillis());
		sendToSink(fio, message);
	}

	private static void processGetTimeNOW(String mesgSplit[]) throws Exception {
		String message = "3 " + CH_ADDR;
		sendToSink(fio, message);
	}

	public static void main(String[] args) throws Exception {
		initAll();
		receiveFrame();
		green.on();
		System.out.println("CLUSTER HEAD READY " + Long.toHexString(CH_ADDR));
	}
}