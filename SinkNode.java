import java.util.HashMap;
import java.util.Map;
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
	
	//Sebenarnya alamat CM gadiperluin di sink
	private static final long[] CM_ADDR = {
			// CLUSTER 1 = 0xBBBB
			0xBBB1, 0xBBB2, 0xBBB3, 
			
			// CLUSTER 2 = 0xCCCC
			0xCCC1, 0xCCC2,
			
			// CLUSTER 3 = 0xDDDD
			0xDDD1, 0xDDD2
	};
	
//	private static Map<Long, Long> cmToCh;
	//Key = alamat CM
	//Value = last SN yang sudah dikirim ACK
	private static Map<Long, Integer> lastSN = new HashMap<>();
	private static Map<Long, Long> rttTABLE = new HashMap<>();
	
	private static AT86RF231 radio;
	private static FrameIO fio;
	private static USART usart;
	private static Shuttle shuttle;
	private static LED green;
	
	private static final long hour7 = 25200000;
	
	private static volatile boolean stop = false;
	private static OutputStream out;
	
	private static Integer SN;
	
	private static void initAll() throws Exception{
		initRadio();	
		initFrameIO();
		initLED();
		resetSN();
		//initMappingAddress();
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
	
	private static void useUSART() throws Exception {
		usart = configUSART();
	}
	
	private static USART configUSART() throws Exception {
		int instanceID = 0;
		USARTParams params = USARTConstants.PARAMS_115200;
		NativeUSART usart = NativeUSART.getInstance(instanceID);
		try {
			usart.close();
			usart.open(params); 
			return usart;
		}
		catch (Exception e) {
			return null;
		}
	}
	
	private static void initLED() throws Exception {
		shuttle = Shuttle.getInstance();

		green = shuttle.getLED(Shuttle.LED_GREEN);
		green.open();
	}
	
	private static void resetSN() throws Exception {
		SN = 0;
	}
	
//	private static void initMappingAddress() {
//		cmToCh = new HashMap<>();
//		
//		for (int i = 0; i < CM_ADDR.length; i++) {
//	        for (int j = 0; j < CM_ADDR[i].length; j++) {
//	            cmToCh.put(CM_ADDR[i][j], CH_ADDR[i]);
//	        }
//	    }
//	}
	
	//=================SEND=====================
	private static void sendToCH (final FrameIO fio, final String mesg, final Integer sn, final long ch_addr) throws Exception { 
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
			
			frame.setSequenceNumber(sn); 
			
			// format pesan : 010 deltaDelay t1
			String message = mesg + " " + Time.currentTimeMillis(); // + t1 : transmit Time;
			frame.setPayload(message.getBytes());
			
			radio.setState(AT86RF231.STATE_TX_ARET_ON);
			fio.transmit(frame); 
		} catch (RadioDriverException e) {
			//e.printStackTrace();
		} catch (NoAckException e) {
			//e.printStackTrace();
		} catch (ChannelBusyException e) {
			//e.printStackTrace();
		} catch (IOException e) {
			
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
	
	private static boolean isCM(long addr) {
	    for (int i = 0; i < CM_ADDR.length; i++) {
            if (CM_ADDR[i] == addr) {
                return true;
            }
	    }
	    return false;
	}
	
	private static void sendACK(long addrCH, long addrCM, int sn) {
	    try {
	        Frame frame = new Frame(Frame.TYPE_DATA | 
	                                Frame.DST_ADDR_16 | 
	                                Frame.INTRA_PAN | 
	                                Frame.SRC_ADDR_16);

	        frame.setSrcAddr(SINK_ADDR);
	        frame.setSrcPanId(COMMON_PANID);
	        
	        frame.setDestAddr(addrCH);
	        frame.setDestPanId(COMMON_PANID);

	        String msg = "ACK " + addrCM + " " + sn;

	        frame.setPayload(msg.getBytes());

	        radio.setState(AT86RF231.STATE_TX_ARET_ON);
	        fio.transmit(frame);

	        System.out.println("Kirim ACK ke CH:" + addrCH + " SN=" + sn);

	    } catch (Exception e) {
	        e.printStackTrace();
	    }
	}
	
	//===================REPLY=====================
	private static void recvReply(final FrameIO fio, final long t0) throws Exception {
		new Thread () {
			@Override
			final Lock lock = new ReentrantLock();  
			public void run() {
				//String levRSSI;
				try { 
					out = usart.getOutputStream(); 
				} catch (Exception e) { 
					e.printStackTrace();
				}
				
				Frame frame = new Frame();
				
				//while ( (count < statusBC.length) && ( (Time.currentTimeMillis()  - t0) <= 1000)) {
				while(!stop) {
					try {
						// receive a frame
						frame = new Frame();
						radio.setState(AT86RF231.STATE_RX_AACK_ON);  
						fio.receive(frame); 
						
						//System.out.println("5. reply dari : " + frame.getSrcAddr());
						
						long t4 = Time.currentTimeMillis();
						
						byte[] payload = frame.getPayload(); 
						String msg = new String(payload, 0, payload.length);
						
						if (msg.startsWith("SENSE")) {
						    processSense(frame, msg);
						    continue;  // 🔥 penting! jangan lanjut ke dispatch
						}
						
						processFrame(frame,t4);	
						
					} catch (Exception e) { 
						//System.out.println("ERROR: " + e.getClass().getName());
					}
				}
				/*
				lock.lock();
				try {
					finrecvReply = true;
					actSensor = count;
				} finally { lock.unlock(); } */
			}
		}.start();
	}
	
	//=================PACKET HANDLER================
	private static void processFrame(final Frame frame, final long t4) throws Exception {
		int code=0; 
		String reply="";
		
		if (frame!=null) {
			byte[] dg = frame.getPayload(); 
			String mesgRecv = new String(dg, 0, dg.length);
			String mesgSplit[] = StringUtils.split(mesgRecv, " ");
			
			//System.out.print("6. Payload " + mesgRecv);

			long srcAddr = Long.parseLong(mesgSplit[1]);			
			String hex_addr = Long.toHexString(srcAddr);
			
			code = Integer.parseInt(mesgSplit[0].trim());
			
			long t1, t2, t3, RTT;
			
			switch (code) {
				case 1 : 
					//cari delta
					//System.out.println("3. Masuk case 1");
					if (isCH(srcAddr)) {
						t1 = Long.parseLong(mesgSplit[2]);
						t2 = Long.parseLong(mesgSplit[3]);
						t3 = Long.parseLong(mesgSplit[4]);
						RTT = t4-t1-(t3-t2); 
						
						if (!rttTABLE.containsKey(srcAddr)) {
							rttTABLE.put(srcAddr, RTT);
						} else {
							rttTABLE.put(srcAddr, rttTABLE.get(srcAddr) + RTT);
						}
						reply = "#HELLO: " + hex_addr + " " + stringFormatTime.SFFull(t3) + " | RTT: " + RTT + "#";
					}
					else {
						t3 = Long.parseLong(mesgSplit[2]);
						RTT = Long.parseLong(mesgSplit[3]);

						reply = "#HELLO: " + hex_addr + " " + stringFormatTime.SFFull(t3) + " | RTT: " + RTT + "#";
					}
					//System.out.println("4. rttTable update: " + rttTABLE[idx]);
					try {
						out.write(reply.getBytes(), 0, reply.length());
						out.flush();
					} catch (IOException e) { 
						//e.printStackTrace(); 
					}
					break;
				case 2 	: 
					//suruh nge set
				 	// format pesan reply : 010 t2 t3 (after set)
					t3 = Long.parseLong(mesgSplit[3]);
					reply = "#SET:" + hex_addr + " change to " + stringFormatTime.SFFull(t3) + "#";
					try {
						out.write(reply.getBytes(), 0, reply.length());
						out.flush();
					} catch (IOException e) { 
						e.printStackTrace(); 
					}
					break;
				case 3	: 
					t3 = Long.parseLong(mesgSplit[4]);
					reply = "#NOW: " + stringFormatTime.SFFull(t3) + " at " + hex_addr + "#";
					try {
						out.write(reply.getBytes(), 0, reply.length());
					} catch (IOException e) { 
						e.printStackTrace(); 
					}
					break;
				default:
					break;
			}
		}
	}
	
	private static void processSense(Frame frame, String msg) {
	    try {
	        String[] parts = StringUtils.split(msg, " ");

	        long srcCH = frame.getSrcAddr();
	        int sn = Integer.parseInt(parts[1]);
	        long srcCM = Long.parseLong(parts[2]);

	        boolean isNew = false;

	        if (!lastSN.containsKey(srcCM)) {
	            lastSN.put(srcCM, sn);
	            isNew = true;
	        } else if (sn == lastSN.get(srcCM) + 1) {
	            lastSN.put(srcCM, sn);
	            isNew = true;
	        }

	        if (isNew) {
	            String mesgRecv = "#" + msg + "#\n";
	            
	            //System.out.print("DATA BARU: " + mesgRecv);
	            
	            out.write(mesgRecv.getBytes());
	            out.flush();
	        } else {
	            //System.out.println("DUPLICATE/OUT-OF-ORDER: " + msg);
	        }

	        // 🔥 cumulative ACK
	        int ack = lastSN.get(srcCM);
	        sendACK(srcCH, srcCM, ack);

	    } catch (Exception e) {
	        e.printStackTrace();
	    }
	}
	
	// ============== SYSTEM MENU =================
	private static void sayHello() throws Exception {
		long t0 = Time.currentTimeMillis();
		recvReply(fio,t0);
		String message = "1 " + t0;
		for (long ch_addr : CH_ADDR) {
			//System.out.println("kirim ke: "+ ch_addr);
			sendToCH(fio, message, SN, ch_addr);
			Thread.sleep(100);
		}
		//sendToCH(fio, message, SN, 0xbbbb);
		Thread.sleep(1000);
	}
	
	private static void setYourTime (int hi) throws Exception {	
		// Format pesan: 010 deltaDelay
		long t0 = Time.currentTimeMillis();
		int snThis = SN++;
		recvReply(fio, t0);
		
		for (long ch_addr : CH_ADDR) {
			if (!rttTABLE.containsKey(ch_addr)) continue;
			long totalRTT = rttTABLE.get(ch_addr);
	        
	        long avgRTT = totalRTT / hi;

	        String message = "2 " + ch_addr + " " + (avgRTT / 2);

	        sendToCH(fio, message, snThis, ch_addr);
	        Thread.sleep(100);
		}
		Thread.sleep(1000);
	}  
	
	private static void tellYourTime() throws Exception {
		long t0 = Time.currentTimeMillis();
		recvReply(fio, t0);
		String message = "3 " + t0;
		for (long ch_addr : CH_ADDR) {
			//System.out.println("kirim ke: "+ ch_addr);
			sendToCH(fio, message, SN, ch_addr);
			Thread.sleep(100);
		}
		Thread.sleep(1000);
	}

	private static void startSense() throws Exception { 
		long t0 = Time.currentTimeMillis();
		recvReply(fio, t0);
		String message = "4 " + Time.currentTimeMillis();
		for (long ch_addr : CH_ADDR) {
			//System.out.println("kirim ke: "+ ch_addr);
			sendToCH(fio, message, SN, ch_addr);
			Thread.sleep(100);
		}
		Thread.sleep(1000);
	}	
	
	private static void stopSense() throws Exception {
		String message = "5 " + Time.currentTimeMillis();
		for (long ch_addr : CH_ADDR) {
			//System.out.println("kirim ke: "+ ch_addr);
			sendToCH(fio, message, SN, ch_addr);
			Thread.sleep(100);
		}
		Thread.sleep(1000);
	}
	
	private static void goExit() throws Exception {
		String message = "6 " + Time.currentTimeMillis();
		for (long ch_addr : CH_ADDR) {
			//System.out.println("kirim ke: "+ ch_addr);
			sendToCH(fio, message, SN, ch_addr);
			Thread.sleep(100);
		}
		Thread.sleep(1000);
		stop = true;
		green.off();
	}
	
	//================MAIN======================
	public static void main(String [] args ) throws Exception { 		
		//int[] menuChoice	= {0,0,0,0,0,0};
		int choice;
		int countCheckOnline = 0;
		boolean isSynced = false;
		boolean isSensing = false;
		
		
		initAll();
		
		green.on();
		
		try { 
			useUSART(); 
		} 
		catch (Exception e) {
			 //e.printStackTrace();
		}
		
		// Synchronize with host have been done
		// time di adjust
		Time.setCurrentTimeMillis(Time.currentTimeMillis() + hour7);
		do {
		    	choice = usart.read();
		    	//choice = 6;
		    	switch (choice) {
		    		case 1: 
		    			// Hi all & get Delta Delay // dan menghitung delay
		    			countCheckOnline = countCheckOnline + 1;
		    			sayHello(); 
		    			break; 
		    		case 2: 
		    			// Synchronize time to all sensor
		    			//menuChoice[choice-1] = menuChoice[choice] + 1;
		    			if (countCheckOnline > 0) {
		    				setYourTime(countCheckOnline);
		    				isSynced = true;
		    			}
		    			break;
		    		case 3: 
		    			// Please tell your time 
		    			// tanpa menghitung delay
		    			//menuChoice[choice] = menuChoice[choice] + 1;
		    			tellYourTime();
		    			break;
		    		case 4:
		    			// GO SENSE and TRANSMIT TO BS
		    			// pilihan ini dilakukan jika sudah synkronisasi waktu
		    			// pilihan ini dilakukan jika startSense belum dipanggil
		    			if (isSynced && !isSensing) {
		    				isSensing = true;
		    				startSense();
		    			}
		    			break;
		    		case 5: 
					//Stop sense
		    			//Hanya terjadi jika menu 4 sedang dilakukan
		    			if (isSensing) {
		    				isSensing = false;
		    				stopSense();
		    			}
		    			break;
		    		case 6: 
		    			//Exit Programme
		    			goExit();
		    			break;
		    		default:
		    			// The user input an unexpected choice.
		    			break;
		    	}
	    } while (choice !=6);
	}
}