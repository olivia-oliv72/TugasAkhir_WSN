import java.io.IOException;
//import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

//import com.virtenio.driver.device.ADXL345;
//import com.virtenio.driver.gpio.GPIO;
//import com.virtenio.driver.gpio.GPIOException;
//import com.virtenio.driver.gpio.NativeGPIO;
//import com.virtenio.driver.spi.NativeSPI;
//import com.virtenio.driver.spi.SPIException;
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
	
	private static int choiceCM = 1;
	private static int[] CM_ADDRs = {0xBBB1, 0xBBB2, 0xEEE1};
	
	private static final int CM_ADDR = CM_ADDRs[choiceCM];
	private static long CH_ADDR = 0;
	
	private static final int WINDOW_SIZE = 5;
	private static final long TIMEOUT = 2000; // 2 detik
	
	private static Integer SN = 0;
	private static int base = 0; //SN paling kecil yang belum di ACK
	private static long timerStart = 0;
	
	private static Map<Integer, String> dataQueue = new HashMap<>();
	private static Map<Integer, String> buffer = new HashMap<>();
		
	private static final int MAX_BUFFER = 50;
	
	private static volatile boolean isSensing = false;
	private static boolean sensingThreadStarted = false;
	private static volatile boolean exit = false;
	
	private static AT86RF231 radio;
	private static FrameIO fio;
	private static Shuttle shuttle;
	private static LED red, green;
	
//	private static ADXL345 acclSensor;
//	private static GPIO accelCs;
	
	private static Sensor sensor = new Sensor();
	
	private static final Lock lock = new ReentrantLock();
	
	private static void initAll() throws Exception {
	    initAddr();
		initRadio();
	    initFrameIO();
	    initLED();
	    resetSN();
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
	
	private static void resetSN() throws Exception {
		SN = 0;
	}
	
	private static void startReceiver(final FrameIO fio) {
		new Thread() {
			public void run() {
				Frame frame = new Frame();
				
				while (!exit) {
					System.out.println("Cluster Member Ready " + Long.toHexString(CM_ADDR));
					try {
						// receive a frame
						radio.setState(AT86RF231.STATE_RX_AACK_ON); 
						fio.receive(frame); 
						long t2 = Time.currentTimeMillis(); 
						processFrame (frame,t2);					
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}.start();
	}
	
	// kirim frame khusus DATA SENSING
	private static void sendSensingFrame(final FrameIO fio, String mesg, Integer sn) throws InterruptedException {
		try {
			String message = "SENSE " + mesg;
			
			Frame frame = new Frame(Frame.TYPE_DATA | 
									Frame.ACK_REQUEST | 
									Frame.DST_ADDR_16 | 
									Frame.INTRA_PAN | 
									Frame.SRC_ADDR_16);
						
			frame.setSrcAddr(CM_ADDR);
			frame.setSrcPanId(COMMON_PANID);
			
			frame.setDestAddr(CH_ADDR);
			frame.setDestPanId(COMMON_PANID);
			
			frame.setSequenceNumber(sn);
			frame.setPayload(message.getBytes());
			
			radio.setState(AT86RF231.STATE_TX_ARET_ON);
			System.out.println(message);
			fio.transmit(frame);
		} catch (RadioDriverException e) { 
			e.printStackTrace();
		} catch (NoAckException e) { 
			
		} catch (ChannelBusyException e) { 
			System.out.println("Channel busy, retry SN=" + sn);
		} catch (IOException e) {
			
		}
	}
	
	// Kirim frame yang bukan data sensing
	private static void startTransmitter (final FrameIO fio, final String mesg) {
		new Thread() {
			@Override
			public void run() 
			{
				boolean isOK = false;
				while (!isOK)
				{
					try {
						int frameControl = 	Frame.TYPE_DATA | 
											//Frame.ACK_REQUEST | 
											Frame.DST_ADDR_16 | 
											Frame.INTRA_PAN | 
											Frame.SRC_ADDR_16;
						
						final Frame frame = new Frame(frameControl);
						
						frame.setSrcAddr(CM_ADDR);
						frame.setSrcPanId(COMMON_PANID);
						
						frame.setDestAddr(CH_ADDR);
						frame.setDestPanId(COMMON_PANID);
												
						String message = mesg + " " + Time.currentTimeMillis(); // + t3: transmit Time;
						//System.out.println("7. " + message);
						frame.setPayload(message.getBytes());
						
						radio.setState(AT86RF231.STATE_TX_ARET_ON);
						fio.transmit(frame); 
						System.out.println("transmitted");
						isOK = true;
					} catch (Exception e) { 
						e.printStackTrace();
					}
				}
				
				
			}
		}.start();
	}
	
	private static void processFrame (final Frame f, final long t2) throws Exception {
		//new Thread () {
			//final Lock lock = new ReentrantLock();
			
			//public void run(){
				int code = -1;
				if (f!=null) {
					try {
						byte[] dg = f.getPayload(); 
						String mesgRecv = new String(dg, 0, dg.length);
						String mesgSplit[] = StringUtils.split(mesgRecv, " ");
				
						if 		(mesgSplit[0].equalsIgnoreCase("1")) code = 1;
						else if (mesgSplit[0].equalsIgnoreCase("2")) code = 2;
						else if (mesgSplit[0].equalsIgnoreCase("3")) code = 3;
						else if (mesgSplit[0].equalsIgnoreCase("4")) code = 4;
						else if (mesgSplit[0].equalsIgnoreCase("5")) code = 5;
						else if (mesgSplit[0].equalsIgnoreCase("6")) code = 6;
						else if (mesgSplit[0].equalsIgnoreCase("ACK")) code = 7;
						switch (code) {
//							case 0 :
//								System.out.println("stop"); 
//								//lock.lock(); try { exit = true; } finally { lock.unlock();}
//								exit = true;
//								break; 
							case 1: 
								//System.out.println("4. menuju processHELLO " + mesgRecv);
								processHELLO(mesgSplit, t2); 
								break; 
							case 2: 
								processSetTimeNOW(mesgSplit,t2); 
								break;
							case 3: 
								processGetTimeNOW(mesgSplit, t2); 
								break;
							case 4:
								//isSensing = true;
								//resetSN();
							    //base = 0;
							    //buffer.clear();
								System.out.println("goSense");
								if (!sensingThreadStarted) {
							        sensingThreadStarted = true;
							        goSense();
							    }
								isSensing = true;
								break; 
							case 5:
								isSensing = false;
								red.off();
								green.on();
								break;
							case 6:
								exit = true;
								green.off();
								break;
							case 7: 
								try {
							        int ackSN = Integer.parseInt(mesgSplit[2]);

							        System.out.println("ACK diterima untuk SN=" + ackSN);
							        
							        lock.lock();
							        try {
								        if (ackSN >= base) {
								            for (int i = base; i <= ackSN; i++) {
								                buffer.remove(i);
								            }
								            System.out.println("Remove hingga SN ke = " + ackSN);
	
								            base = ackSN + 1;
								            
								            timerStart = Time.currentTimeMillis();
								        }
								        
								        int totalBuffer = dataQueue.size() + buffer.size();
	
								        if (totalBuffer >= MAX_BUFFER) {
								            isSensing = false;
								        } else if (totalBuffer < MAX_BUFFER / 2) {
								            isSensing = true;
								        }
								        
							        } finally {
							        		lock.unlock();
								    }
							    } catch (Exception e) {
							        e.printStackTrace();
							    }
								break;
							default:
								break;
						}
					} catch (Exception e) { 
						e.printStackTrace();
					}
				}
			//}
		//}.start();
	}

	private static void processHELLO(final String mesgSplit[], final long t2) throws Exception {	
		// after frame received, this node process it and transmit frame for reply
		long t1 = Long.parseLong(mesgSplit[2]); // time from CH = transmit time from BS
		String message = "1 " + CM_ADDR + " " + t1 + " " + t2; 
		//System.out.println("5. " + message);
		//System.out.println("6. menuju startTransmitter");
		lock.lock();
		try {
		    startTransmitter(fio, message);
		} finally {
		    lock.unlock();
		}
	}
	
	private static void processSetTimeNOW(String mesgSplit[], long t2) throws Exception { 
		// format pesan: 010 deltaDelay t1
		// set Time
		Time.setCurrentTimeMillis(
				Long.parseLong(mesgSplit[3]) + 
				Long.parseLong(mesgSplit[2]) + 
				(Time.currentTimeMillis() - t2) );

		// format pesan reply : 010 t2 t3 (time after set)
		String message = "2 " + CM_ADDR + " " + (Time.currentTimeMillis());
		System.out.println(stringFormatTime.SFFull(Time.currentTimeMillis()));
		lock.lock();
		try {
		    startTransmitter(fio, message);
		} finally {
		    lock.unlock();
		}
	}
	
	private static void processGetTimeNOW(String mesgSplit[], long t2) throws Exception { 
		// untuk memproses Get time NOW
		// <KodePesan>space<Pesan1>space<Pesan2> = 01 t1 t1
		// 01 t2 t1
		long t1 = Long.parseLong(mesgSplit[2]);
		String message = "3 " + CM_ADDR + " " + t1 + " " + t2;
		lock.lock();
		try {
		    startTransmitter(fio, message);
		} finally {
		    lock.unlock();
		}
	}
	
	// Sensing and transmit 
	private static void goSense() throws Exception {
		green.off();
		red.on();
		
		new Thread() { 
			public void run() {
				String valStr;
//				short[] valAccl = new short[3];
				long getT;
				
//				try { 
//					initACCL();
//				} catch (Exception e) { 
//					e.printStackTrace();
//				}		
				
				while (!exit) {
					try { 
						
						if (!isSensing) {
				            Thread.sleep(100);
				            continue;
				        }
						getT = Time.currentTimeMillis(); 
						//acclSensor.getValuesRaw(valAccl, 0);
						
						String sensorData;
						
						try {
						    sensorData = sensor.readAll();
						} catch (Exception e) {
						    e.printStackTrace();
						    sensorData = "ERROR"; // fallback biar tetap jalan
						}

						valStr = SN + " " + CM_ADDR + " " + sensorData;
		
						//valStr = SN + " " + CM_ADDR + " " + stringFormatTime.SFFull(getT) + " " + Arrays.toString(valAccl);
						
						lock.lock();
						int totalBuffer;
						try {
						    totalBuffer = dataQueue.size() + buffer.size();
						} finally {
						    lock.unlock();
						}

						if (totalBuffer >= MAX_BUFFER) {
						    isSensing = false;
						    continue;
						}
						
						lock.lock();
						try {
						    dataQueue.put(SN, valStr);
						    SN++;
						} finally {
						    lock.unlock();
						}

	                    //System.out.println("QUEUE SN=" + SN);
											
	                    if (dataQueue.size() > MAX_BUFFER * 0.8) {
	                        Thread.sleep(500); // memperlambat sensing jika buffer uda mau penuh
	                    } else {
	                        Thread.sleep(100); // normal
	                    }
						
					} catch (InterruptedException e) {
						e.printStackTrace();
					} catch (Exception e) {
					    e.printStackTrace();
					};
				}
				
				//DEBUG
				System.out.println("Keluar goSense");
			}
		}.start();
	}
	
	private static void startSender() {
	    new Thread() {
	        public void run() {
	            while (!exit) {
	                try {
	                    // isi window selama masih bisa

		                	lock.lock();
		                	try {
		                	    for (int i = base; i < SN && i < base + WINDOW_SIZE; i++) {
		                	        if (buffer.get(i) == null) {
		                	            String data = dataQueue.get(i);
	
		                	            if (data != null) {
		                	            		if (i == base && buffer.get(i) == null) {
		                	                    timerStart = Time.currentTimeMillis();
		                	                }
		                	                buffer.put(i, data);
		                	                dataQueue.remove(i);
		                	                System.out.println(data);
		                	                sendSensingFrame(fio, data, i);
		                	            }
		                	        }
		                	    }
		                	} finally {
		                	    lock.unlock();
		                	}	                   
	                    Thread.sleep(10); // kecil aja

	                } catch (Exception e) {
	                    e.printStackTrace();
	                }
	            }
	        }
	    }.start();
	}
	
//	private static void initACCL() throws Exception {
//		accelCs = NativeGPIO.getInstance(20); // init GPIO
//		NativeSPI spi = NativeSPI.getInstance(0); // init SPI
//		spi.open(ADXL345.SPI_MODE, ADXL345.SPI_BIT_ORDER, ADXL345.SPI_MAX_SPEED); // open SPI
//		// Inisiasi ADXL345
//		acclSensor = new ADXL345(spi,accelCs);
//		acclSensor.open();
//		acclSensor.setPowerControl(ADXL345.POWER_MODE_NORMAL);
//		acclSensor.setDataFormat(ADXL345.DATA_FORMAT_RANGE_16G); 
//		acclSensor.setDataRate(ADXL345.DATA_RATE_100HZ);
//		acclSensor.setPowerControl(ADXL345.POWER_CONTROL_MEASURE);
//	}
	
	private static void startTimer() {
		new Thread() { //thread untuk timer seperti watch dog untuk Go Back N
		    public void run() {
		        while (!exit) {
		            try {
		                if (!buffer.isEmpty()) { //Apakah masi ada data yang belum di ACK ?
		                    long now = Time.currentTimeMillis(); // waktu skrg

		                    if (now - timerStart > TIMEOUT) { //uda berapa lama sejak frame dikirim

		                        System.out.println("TIMEOUT → Go Back N dari SN=" + base);
		                        
		                        lock.lock();
		                        try {
			                        	for (int i = base; i < SN; i++) {
			                        		String data = buffer.get(i);
			                        		
			                        		if (data != null) {
			                        			sendSensingFrame(fio, data, i);
			                        		}
			                        	}
		                        } finally {
		                        	lock.unlock();
		                        }

		                        timerStart = now;
		                    }
		                }

		                Thread.sleep(100);

		            } catch (Exception e) {
		                e.printStackTrace();
		            }
		        }
		    }
		}.start();
	}
	
	private static void run() {
		try {
			initAll();
			
			green.on();
			
			startReceiver(fio);
			startTimer();
			startSender();
		} catch (Exception e) { 
			e.printStackTrace(); 
		}
	}
		
	public static void main(String [] args) throws Exception {
		run();
	}
}