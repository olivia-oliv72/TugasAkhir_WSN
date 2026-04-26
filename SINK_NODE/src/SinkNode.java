import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Lock;
import java.io.IOException;
import java.io.OutputStream;

import com.virtenio.driver.usart.NativeUSART;
import com.virtenio.driver.usart.USART;
import com.virtenio.driver.usart.USARTException;
import com.virtenio.driver.usart.USARTParams;
import com.virtenio.driver.device.at86rf231.*;
import com.virtenio.driver.led.LED;
import com.virtenio.io.ChannelBusyException;
import com.virtenio.io.NoAckException;
import com.virtenio.misc.StringUtils;
import com.virtenio.preon32.examples.common.USARTConstants;
import com.virtenio.preon32.shuttle.Shuttle;
import com.virtenio.preon32.examples.common.RadioInit;
import com.virtenio.radio.RadioDriverException;
import com.virtenio.radio.ieee_802_15_4.Frame;
import com.virtenio.radio.ieee_802_15_4.FrameIO;
import com.virtenio.radio.ieee_802_15_4.RadioDriver;
import com.virtenio.radio.ieee_802_15_4.RadioDriverFrameIO;
import com.virtenio.vm.Time;

public class SinkNode {
	private static final int COMMON_CHANNEL = 24;
	private static final int COMMON_PANID = 0xCAFE;
	
	// ================ ADDRESS ==============
	private static final int SINK_ADDR = 0xAAAA;
	private static final long[] CH_ADDR = {
			0xBBBB,  // CH cluster 1
			0xCCCC,  // CH cluster 2
			0xDDDD   // CH cluster 3
	};
	
	private static Map<Long, Long> rttTABLE = new HashMap<>();
	private static Map<Long, Set<Integer>> receivedSN = new HashMap<>();
	private static final int MAX_TRACK = 100;
	
	private static AT86RF231 radio;
	private static FrameIO fio;
	private static USART usart;
	private static Shuttle shuttle;
	private static LED green;
	
//	private static final long hour7 = 25200000;
	
	private static volatile boolean stop = false;
	private static OutputStream out;
		
	private static void initAll() throws Exception{
		initRadio();	
		initFrameIO();
		initUSART();
		initLED();
	}
	
	private static void initRadio() throws Exception {
		radio = RadioInit.initRadio();
		radio.setChannel(COMMON_CHANNEL);
		radio.setPANId(COMMON_PANID);
		radio.setShortAddress(SINK_ADDR);
	}
	
	private static void initFrameIO() throws Exception {
		final RadioDriver radioDriver = new AT86RF231RadioDriver(radio);
		fio = new RadioDriverFrameIO(radioDriver);	
	}
	
	private static void initUSART() {
	    try {
	        int instanceID = 0;
	        USARTParams params = USARTConstants.PARAMS_115200;

	        usart = NativeUSART.getInstance(instanceID);

	        usart.close();
	        usart.open(params);

	        out = usart.getOutputStream();

	    } catch (Exception e) {
	    		threadSleep(50);
	    }
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
	
	private static boolean isCH(long addr) {
	    for (long ch : CH_ADDR) {
	        if (ch == addr) {
	            return true;
	        }
	    }
	    return false;
	}
	
	private static void handleCommand() throws Exception {
		int choice;
		int countCheckOnline = 0;
		boolean isSynced = false;
		boolean isSensing = false;
		String message;
		
		do {
		    	choice = usart.read();
		    	switch (choice) {
		    		case 1: 
		    			countCheckOnline = countCheckOnline + 1;
		    			message = "1";
		    			for (long ch_addr : CH_ADDR) {
		    				transmitFrame(fio, message, ch_addr);
		    				Thread.sleep(50); //Delay bukan untuk pengiriman, tapi delay untuk terima data dari CH agar ga collision dengan CH lain, kl ga dipakein lgsg collision jadi byk yang null
		    			}
		    			break; 
		    		case 2: 
		    			// Synchronize time to all sensor
		    			if (countCheckOnline > 0) {
		    				for (long ch_addr : CH_ADDR) {
		    					if (!rttTABLE.containsKey(ch_addr)) continue;
		    					
		    					long totalRTT = rttTABLE.get(ch_addr);
		    			        long avgRTT = totalRTT / countCheckOnline;
		    			        message = "2 " + ch_addr + " " + (avgRTT / 2);
		    			        transmitFrame(fio, message, ch_addr);
		    			        Thread.sleep(50);
		    				}
		    				isSynced = true;
		    			}
		    			break;
		    		case 3: 
		    			//Get Time
		    			message = "3";
		    			for (long ch_addr : CH_ADDR) {
		    				transmitFrame(fio, message, ch_addr);
		    				Thread.sleep(50);
		    			}
		    			break;
		    		case 4:
		    			// pilihan ini dilakukan jika sudah synkronisasi waktu & startSense tidak pernah dipanggil
		    			if (isSynced && !isSensing) {
		    				isSensing = true;
		    				message = "4";
		    				for (long ch_addr : CH_ADDR) {
		    					for(int i=0; i<3 ; i++) {
		    						transmitFrame(fio, message, ch_addr);
//		    						Thread.sleep(50);
		    					}
		    				}
		    			}
		    			break;
		    		case 5: 
		    			//Exit Programme
		    			message = "5";
		    			for (long ch_addr : CH_ADDR) {
		    				for(int i=0; i<3 ; i++) {
		    					transmitFrame(fio, message, ch_addr);
//		    					Thread.sleep(50);
		    				}
		    			}
		    			stop = true;
		    			green.off();
		    			radio.setState(AT86RF231.STATE_TRX_OFF);
		    			break;
		    		default:
		    			// The user input an unexpected choice.
		    			break;
		    	}
	    } while (choice !=5);
	}
	
	private static void receiveFrame (final FrameIO fio) throws Exception {
		new Thread () {			
			@Override
			public void run() {
				Frame frame = new Frame();
				
				while(!stop) {
					try {
						frame = new Frame();
						radio.setState(AT86RF231.STATE_RX_AACK_ON);  
						fio.receive(frame); 
						
						long tReceivedFrame = Time.currentTimeMillis();
												
						processFrame(frame,tReceivedFrame);	
					} catch (Exception e) { 
						threadSleep(50);
					}
				}
			}
		}.start();
	}
	
	private static void transmitFrame (final FrameIO fio, final String mesg, final long ch_addr) throws Exception { 
		try {
			Frame frame = new Frame(Frame.TYPE_DATA | 
									Frame.ACK_REQUEST | 
									Frame.DST_ADDR_16 | 
									Frame.INTRA_PAN | 
									Frame.SRC_ADDR_16); 
			
			frame.setSrcAddr(SINK_ADDR);
			frame.setSrcPanId(COMMON_PANID);
			
			frame.setDestAddr(ch_addr); 
			frame.setDestPanId(COMMON_PANID);
			
			String message = mesg + " " + Time.currentTimeMillis(); // + t1 : transmit Time;
			frame.setPayload(message.getBytes());
			
			radio.setState(AT86RF231.STATE_TX_ARET_ON);
			fio.transmit(frame); 
		} catch (RadioDriverException e) {
			threadSleep(50);
		} catch (NoAckException e) {
			threadSleep(50);
		} catch (ChannelBusyException e) {
			threadSleep(50);
		} catch (IOException e) {
			threadSleep(50);
		}
	}
	
	private static void sendToUSART(String msg) {
	    try {
	        out.write(msg.getBytes(), 0, msg.length());
	        out.flush();
	    } 
	    catch (IOException e) {
	        //e.printStackTrace();
		    	try {
		    		Thread.sleep(50);
	        } 
		    	catch (InterruptedException err) {
	        }
	    }
	}
	
	private static void processFrame(final Frame frame, final long t4) throws Exception {
		String code = ""; 
		String reply = "";
		
		byte[] payload = frame.getPayload(); 
		String message = new String(payload, 0, payload.length);
		String mesgSplit[] = StringUtils.split(message, " ");
		
		code = mesgSplit[0].trim();
		long srcAddr = Long.parseLong(mesgSplit[1]);			
		
		switch (code) {
			case "1" : 
				if (isCH(srcAddr)) { //Dari CH --> hitung Round Trip Time
					long t1 = Long.parseLong(mesgSplit[2]); 	// t1 = Waktu frame dikirim dari sink
					long t2 = Long.parseLong(mesgSplit[3]); 	// t2 = Waktu frame sampai ke CH
					long t3 = Long.parseLong(mesgSplit[4]); 	// t3 = Waktu frame dikirim dari CH
															// t4 = Waktu frame sampai ke sink
					long RTT = t4-t1-(t3-t2); 
					if (!rttTABLE.containsKey(srcAddr)) {
						rttTABLE.put(srcAddr, RTT);
					} else {
						rttTABLE.put(srcAddr, rttTABLE.get(srcAddr) + RTT);
					}
					reply = "#" + srcAddr + " " + t3 + " " + RTT + " CH#";
				}
				else { //Dari CM --> hanya print
					String time = mesgSplit[2];
					String RTT = mesgSplit[3];
					String chAddr = mesgSplit[4];

					reply = "#" + srcAddr + " " + time + " " + RTT + " " + chAddr + "#";
				}
				sendToUSART(reply);
				break;
			case "2" 	: 
				String time = mesgSplit[3];
				reply = "#" + srcAddr + " " + time + "#";
				sendToUSART(reply);
				break;
			case "3"	: 
				time = mesgSplit[2];
				reply = "#" + srcAddr + " " + time + "#";
				sendToUSART(reply);
				break;
			case "4":
				processSense(frame);
				break;
			default:
				break;
		}
	}
	
	private static void processSense(Frame frame) {
	    try {
		    	long srcCH = frame.getSrcAddr();
	    		byte[] payload = frame.getPayload(); 
	    		
			String message = new String(payload, 0, payload.length);
		    	String[] parts = StringUtils.split(message.trim(), " ");

            int sn = Integer.parseInt(parts[1]);
            long srcCM = Long.parseLong(parts[2]);

            Set<Integer> snSet = receivedSN.get(srcCM);
            
            if (snSet == null) {
                snSet = new TreeSet<>();
                receivedSN.put(srcCM, snSet);
            }
            boolean isNew = !snSet.contains(sn);

            if (isNew) {
            		snSet.add(sn);
            		
            		if (snSet.size() > MAX_TRACK) {
                        snSet.remove(Collections.min(snSet));
            		}

                String reply = "# " + message + "#";
                sendToUSART(reply);
            }

            sendACK(srcCH, srcCM, sn);
	    } catch (Exception e) {
	    		threadSleep(50);
	    }
	}
	
	private static void sendACK(long addrCH, long addrCM, int sn) throws Exception{
        String message = "ACK " + addrCM + " " + sn;
        transmitFrame(fio, message, addrCH);
	}
	
	public static void main(String [] args ) throws Exception { 		
		initAll();
//		Time.setCurrentTimeMillis(Time.currentTimeMillis() + hour7);
		green.on();
		
		receiveFrame(fio);
		handleCommand();
	}
}