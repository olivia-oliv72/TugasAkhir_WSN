import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.virtenio.io.ChannelBusyException;
import com.virtenio.io.NoAckException;
import com.virtenio.misc.StringUtils;
import com.virtenio.preon32.examples.common.RadioInit;
import com.virtenio.preon32.shuttle.Shuttle;
import com.virtenio.radio.ieee_802_15_4.RadioDriver;
import com.virtenio.radio.ieee_802_15_4.Frame;
import com.virtenio.radio.ieee_802_15_4.FrameIO;
import com.virtenio.radio.ieee_802_15_4.RadioDriverFrameIO;
import com.virtenio.radio.RadioDriverException;

import com.virtenio.driver.device.at86rf231.*;
import com.virtenio.driver.device.at86rf231.AT86RF231RadioDriver;
import com.virtenio.driver.led.LED;

import com.virtenio.vm.Time;

public class ClusterMember {
	private static final int COMMON_CHANNEL = 24;
	private static final int COMMON_PANID = 0xCAFE;
	
	private static int choiceCM = 2;
	private static int[] CM_ADDRs = {0xBBB1, 0xBBB2, 0xEEE1};
	
	private static final int CM_ADDR = CM_ADDRs[choiceCM];
	private static long CH_ADDR = 0;
	
	private static int SN = 0;
	private static Map<Integer, String> pendingData = new LinkedHashMap<>();
	private static Map<Integer, String> unackedData = new HashMap<>();
	private static Map<Integer, Long> sentTime = new HashMap<>();
	private static final long TIMEOUT = 2000; // 2 detik
		
	private static final int MAX_BUFFER = 50;
	
	private static volatile boolean isSensing = false;
	private static boolean sensingThreadStarted = false;
	private static volatile boolean exit = false;
	
	private static AT86RF231 radio;
	private static FrameIO fio;
	private static Shuttle shuttle;
	private static LED red, green;
	
	private static Sensor sensor = new Sensor();
	
	private static final Lock lock = new ReentrantLock();
	
	private static void initAll() throws Exception {
	    initAddr();
		initRadio();
	    initFrameIO();
	    initLED();
	}
	
	private static void initAddr() {
		switch (CM_ADDR) {
			case 0xBBB1:
				CH_ADDR = 0xBBBB;
				break;
			case 0xBBB2:
				CH_ADDR = 0xBBBB;
				break;
			case 0xEEE1:
				CH_ADDR = 0xBBB3;
				break;
		}
	}
	
	private static void initRadio() throws Exception {
		radio = RadioInit.initRadio();
		radio.setChannel(COMMON_CHANNEL);
		radio.setPANId(COMMON_PANID);
		radio.setShortAddress(CM_ADDR);
	}
	
	private static void initFrameIO() throws Exception {
		final RadioDriver radioDriver = new AT86RF231RadioDriver(radio);
		fio = new RadioDriverFrameIO(radioDriver);	
	}
	
	private static void initLED() throws Exception {
		shuttle = Shuttle.getInstance();

		green = shuttle.getLED(Shuttle.LED_GREEN);
		green.open();
		
		red = shuttle.getLED(Shuttle.LED_RED);
		red.open();
	}
	
	private static void threadSleep(int ms) {
	    try {
	        Thread.sleep(ms);
	    } catch (InterruptedException e) {
	        Thread.currentThread().interrupt();
	    }
	}
	
	private static void receiveFrame(final FrameIO fio) {
		new Thread() {
			@Override
			public void run() {
				Frame frame = new Frame();
				
				while (!exit) {
					try {
						radio.setState(AT86RF231.STATE_RX_AACK_ON); 
						fio.receive(frame); 
						
						long tReceivedFrame = Time.currentTimeMillis(); 
						processFrame (frame,tReceivedFrame);					
					} catch (Exception e) {
						threadSleep(50);
					}
				}
			}
		}.start();
	}
	
	// Kirim frame yang bukan data sensing
	private static void transmitFrame (final FrameIO fio, final String mesg) {
		try {
			int frameControl = 	Frame.TYPE_DATA | 
//								Frame.ACK_REQUEST | 
								Frame.DST_ADDR_16 | 
								Frame.INTRA_PAN | 
								Frame.SRC_ADDR_16;
			
			final Frame frame = new Frame(frameControl);
			
			frame.setSrcAddr(CM_ADDR);
			frame.setSrcPanId(COMMON_PANID);
			
			frame.setDestAddr(CH_ADDR);
			frame.setDestPanId(COMMON_PANID);
									
			String message = mesg + " " + Time.currentTimeMillis(); // + t3: transmit Time;
			frame.setPayload(message.getBytes());
			
			radio.setState(AT86RF231.STATE_TX_ARET_ON);
			fio.transmit(frame); 
		} catch (Exception e) { 
			threadSleep(50);
		}
	}
	
	private static void processFrame (final Frame f, final long t2) throws Exception {
		String code;
		if (f!=null) {
			try {
				byte[] dg = f.getPayload(); 
				String mesgRecv = new String(dg, 0, dg.length);
				String mesgSplit[] = StringUtils.split(mesgRecv, " ");
				
				code = mesgSplit[0];
				switch (code) {
					case "1": 
						processHELLO(mesgSplit, t2); 
						break; 
					case "2": 
						processSetTimeNOW(mesgSplit,t2); 
						break;
					case "3": 
						processGetTimeNOW(mesgSplit); 
						break;
					case "4":
						System.out.println("goSense");
						if (!sensingThreadStarted) {
					        sensingThreadStarted = true;
					        startTimer();
					        startSender();
					        goSense();
					    }
						isSensing = true;
						break;
					case "5":
						isSensing = false;
						exit = true;
						green.off();
						red.off();
						radio.setState(AT86RF231.STATE_TRX_OFF);
						break;
					case "ACK":
					    try {
					        int ackSN = Integer.parseInt(mesgSplit[2]);

					        //System.out.println("ACK diterima untuk SN=" + ackSN);

					        lock.lock();
					        try {
					            if (unackedData.containsKey(ackSN)) {
					                unackedData.remove(ackSN);
					                sentTime.remove(ackSN);

					                //System.out.println("ACK OK, remove seq=" + ackSN);
					            }

//					            int totalBuffer = pendingData.size() + unackedData.size();
//
//					            if (totalBuffer >= MAX_BUFFER) {
//					                isSensing = false;
//					            } else if (totalBuffer < MAX_BUFFER / 2) {
//					                isSensing = true;
//					            }

					        } finally {
					            lock.unlock();
					        }

					    } catch (Exception e) {
					    		threadSleep(50);
					    }
					    break;
					default:
						break;
				}
			} catch (Exception e) { 
				threadSleep(50);
			}
		}
	}

	private static void processHELLO(final String mesgSplit[], final long t2) throws Exception {	
		long t1 = Long.parseLong(mesgSplit[1]); // Waktu CH kirim frame
		String message = "1 " + CM_ADDR + " " + t1 + " " + t2; 
		transmitFrame(fio, message);
	}
	
	private static void processSetTimeNOW(String mesgSplit[], long t2) throws Exception { 
		Time.setCurrentTimeMillis (Long.parseLong(mesgSplit[3]) + Long.parseLong(mesgSplit[2]) + (Time.currentTimeMillis() - t2));

		String message = "2 " + CM_ADDR;
		transmitFrame(fio, message);
	}
	
	private static void processGetTimeNOW(String mesgSplit[]) throws Exception { 
		String message = "3 " + CM_ADDR;
		transmitFrame(fio, message);
	}
	
	private static void goSense() throws Exception {
		green.off();
		red.on();
		
		new Thread() { 
			@Override
			public void run() {
				while (!exit) {
					try { 
						if (!isSensing) {
//				            Thread.sleep(50);
				            continue;
				        }
						
						String sensorData = null;
						try {
						    sensorData = sensor.readAll();
						} catch (Exception e) {
							threadSleep(50);
						}
						
						if (sensorData == null) continue;

						sensorData = sensorData.trim();

						if (sensorData.isEmpty() || sensorData.equals("ERROR")) {
						    continue;
						}
						
						lock.lock();
						int totalBuffer;
						try {
						    totalBuffer = pendingData.size() + unackedData.size();
						} finally {
						    lock.unlock();
						}

						if (totalBuffer >= MAX_BUFFER) {
						    Thread.sleep(200);
						    continue;
						}
						
						String currentData = SN + " " + CM_ADDR + " " + sensorData;
						System.out.println(currentData);
						
						lock.lock();
					    try {
						    	pendingData.put(SN, currentData);
					    } finally {
					        lock.unlock();
					    }

						SN++;
						
						int size;
						lock.lock();
						try {
						    size = pendingData.size();
						} finally {
						    lock.unlock();
						}
											
	                    if (size > MAX_BUFFER * 0.8) {
	                        Thread.sleep(100); // memperlambat sensing jika buffer uda mau penuh
	                    } else {
	                        Thread.sleep(50); // normal
	                    }
					} catch (InterruptedException e) {
						threadSleep(50);
					} catch (Exception e) {
						threadSleep(50);
					};
				}
			}
		}.start();
	}
	
	private static void startSender() {
	    new Thread() {
	    		@Override
	        public void run() {
	            while (!exit) {
	                	lock.lock();
	                	try {
	                		if (!pendingData.isEmpty()) {

	                	        // ambil 1 data (yang pertama)
	                	        int seq = pendingData.keySet().iterator().next();
	                	        String data = "4 " + pendingData.get(seq);

	                	        try {
	                	        		transmitFrame(fio, data);
	                	        } catch (Exception e) {
	                	        		threadSleep(50);
	                	        }

	                	        // pindah ke unackedData
	                	        unackedData.put(seq, data);

	                	        // simpan waktu kirim
	                	        sentTime.put(seq, Time.currentTimeMillis());

	                	        // hapus dari pendingData
	                	        pendingData.remove(seq);
	                	    }
	                	} finally {
	                	    lock.unlock();
	                	}	                   
	            }
	        }
	    }.start();
	}
	
	private static void startTimer() {
	    new Thread() {
	    		@Override
	        public void run() {
	            while (!exit) {
	                try {
	                    long now = Time.currentTimeMillis();

	                    lock.lock();
	                    try {
		                    	int resendCount = 0;
	
		                    	for (int seq : unackedData.keySet()) {
		                    	    long last = sentTime.get(seq);
		                    	    if (now - last > TIMEOUT) {
		                    	        String data = unackedData.get(seq);
		                    	        System.out.println("RESEND seq=" + seq);
		                    	        transmitFrame(fio, data);
		                    	        sentTime.put(seq, now);
		                    	        resendCount++;
	
		                    	        // 🔥 BATASIN!
		                    	        if (resendCount >= 2) break;
		                    	    }
		                    	}
	                    } finally {
	                        lock.unlock();
	                    }
	                } catch (Exception e) {
	                		threadSleep(50);
	                }
	            }
	        }
	    }.start();
	}
	
	private static void run() {
		try {
			initAll();
			green.on();
			
			receiveFrame(fio);
		} catch (Exception e) { 
			threadSleep(50);
		}
	}
		
	public static void main(String [] args) throws Exception {
		run();
		System.out.println("Cluster Member Ready " + Long.toHexString(CM_ADDR));
	}
}