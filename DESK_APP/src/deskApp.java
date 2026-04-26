import com.virtenio.commander.io.DataConnection;
import com.virtenio.commander.toolsets.preon32.Preon32Helper;
import com.fazecast.jSerialComm.SerialPort;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;
import org.apache.tools.ant.DefaultLogger;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.*;
import java.util.*;
import java.io.File;


public class deskApp {
	Calendar cal = Calendar.getInstance();
	
	private BufferedWriter writer;
	private Scanner choice; 
	private volatile boolean exit = false;
	
	Map<String, List<String>> clusterMap =  new HashMap<>();;
	Map<String, String> nodeInfo;
	
	Map<Integer, TreeMap<Integer, String>> nodeBuffer = new HashMap<>();
	Map<Integer, Integer> expectedSeqMap = new HashMap<>();
	
	Preon32Helper c;
	private ArrayList<SerialPort> listSerialPorts;
	
	public void writeToFile(String fName, String folName, BufferedInputStream in) throws Exception {
		new Thread() {	
			byte[] buffer = new byte[256];
			File newFolder = new File(folName);
			@Override
			public void run() {
				// disini check apakah sdh ada direktori hari ini
				// jika belum dibuat
				// maka semua file harus berada di direktori hari ini.
				
				//sampe sini berhasil
				if (!newFolder.exists()) newFolder.mkdir();
				String path = folName + "/" + fName;
				try {
					FileWriter fw = new FileWriter(path);
					writer = new BufferedWriter(fw);
					//sampe sini berhasil
				} catch (Exception e) { 
					e.printStackTrace();
				}
				StringBuilder sb = new StringBuilder();

				while (!exit) {
				    try {
				        int len = in.read(buffer);

				        if (len > 0) {
				            String s = new String(buffer, 0, len);
				            sb.append(s);

				            while (sb.indexOf("#") != -1) {
				                int end = sb.indexOf("#");
				                String msg = sb.substring(0, end);
				                sb.delete(0, end + 1);
				                
					            String[] parts = msg.trim().split(" ");

					            if (parts[0].equals("4")) {
				                    try {
				                        // safety dulu
				                        if (parts.length < 8) {
				                            System.out.println("Data tidak lengkap: " + msg);
				                            continue;
				                        }
				                        
				                        int seq  = Integer.parseInt(parts[1]);
				                        int node = Integer.parseInt(parts[2]);
				                        
				                        // data sensor
				                        String[] accel = parts[3].split(",");
				                        if (accel.length < 3) {
				                            System.out.println("ACCL error: " + msg);
				                            continue;
				                        }
				                        
				                        String temp = parts[4];
				                        String hum  = parts[5];
				                        String press = parts[6];
				                        
				                        long time = Long.parseLong(parts[7]);
				                        
				                        String line =    
				                        			   " Time=" + stringFormatTime.SFTime(time) +
					                                " Node=" + node +
					                                " Seq=" + seq +
					                                " ACCL=[" + accel[0] + "," + accel[1] + "," + accel[2] + "]" +
					                                " TEMP=" + temp + "°C" +
					                                " HUM=" + hum + "%" +
					                                " PRESS=" + press + "kPa";
				                        
//				                        System.out.println(line);
				                        
				                        nodeBuffer.putIfAbsent(node, new TreeMap<>());
				                        expectedSeqMap.putIfAbsent(node, 0);

				                        nodeBuffer.get(node).put(seq, line);

//				                        dataBuffer.put(seq, line);

				                        TreeMap<Integer, String> buffer = nodeBuffer.get(node);
				                        int expected = expectedSeqMap.get(node);

				                        while (buffer.containsKey(expected)) {
				                            String outLine = buffer.remove(expected);

				                            outLine = "DESKAPP=" + stringFormatTime.SFTime(System.currentTimeMillis()) + outLine;

				                            writer.write(outLine);
				                            writer.newLine();
				                            writer.flush();

				                            System.out.println(outLine);

				                            expected++;
				                        }

				                        expectedSeqMap.put(node, expected);

				                    } catch (Exception e) {
				                        System.out.println("Parse error: " + msg);
				                    }
				                }
				            }
				        }

				    } catch (IOException e) {}
				}
			}
		}.start();
	}
	
	
	private void context_set(String target) throws Exception
	{
		DefaultLogger consoleLogger = getConsoleLogger();
		// Prepare ant project
		File buildFile = new File ("C:\\Users\\oliv\\eclipse-workspace-TA\\SINK_NODE\\buildUser.xml");
		Project antProject = new Project();
		antProject.setUserProperty("ant.file", buildFile.getAbsolutePath());
		antProject.addBuildListener(consoleLogger);
		
		try {
			antProject.fireBuildStarted();
			antProject.init();
			ProjectHelper helper = ProjectHelper.getProjectHelper();
			antProject.addReference("ant.ProjectHelper", helper);
			helper.parse(antProject, buildFile);
			//
			antProject.executeTarget(target);
			antProject.fireBuildFinished(null);
		} catch (BuildException e) { e.printStackTrace();}
	}

	private void time_synchronize() throws Exception {
		DefaultLogger consoleLogger = getConsoleLogger();
		File buildFile = new File ("C:\\Users\\oliv\\eclipse-workspace-TA\\SINK_NODE\\build.xml");
		Project antProject = new Project();
		antProject.setUserProperty("ant.file", buildFile.getAbsolutePath());
		antProject.addBuildListener(consoleLogger);
		
		try {
			antProject.fireBuildStarted();
			antProject.init();
			ProjectHelper helper = ProjectHelper.getProjectHelper();
			antProject.addReference("ant.ProjectHelper", helper);
			helper.parse(antProject, buildFile);
			//
			String target = "cmd.time.synchronize";
			antProject.executeTarget(target);
			antProject.fireBuildFinished(null);
		} catch (BuildException e) { e.printStackTrace();}
	}
	
	public void showMenu(int input1, int input2, int input4) {

	    final String GRAY = "\u001B[90m";
	    final String RESET = "\u001B[0m";
	    System.out.println();
	    System.out.println("==============================");
	    System.out.println("             MENU             ");
	    System.out.println("==============================");

	    // 1. Deteksi Node
	    if (input4 == 1) {
	        System.out.println(GRAY + "1. Deteksi Node Aktif" + RESET);
	    } else {
	        System.out.println("1. Deteksi Node Aktif");
	    }

	    // 2 & 3
	    if (input4 == 1 || input1 == 0) {
	        System.out.println(GRAY + "2. Sinkronisasi Waktu Node" + RESET);
	        System.out.println(GRAY + "3. Ambil Waktu Node" + RESET);
	    } else {
	        System.out.println("2. Sinkronisasi Waktu Node");
	        System.out.println("3. Ambil Waktu Node");
	    }

	    // 4
	    if (input4 == 1 || input2 == 0) {
	        System.out.println(GRAY + "4. Mulai Proses Sensing" + RESET);
	    } else {
	        System.out.println("4. Mulai Proses Sensing");
	    }

	    // 5
	    System.out.println("5. Keluar");

	    System.out.println("------------------------------");
	    System.out.print("Pilihan: ");
	}

	public void init() throws Exception {
		stringFormatTime sfTime = new stringFormatTime();
		try {
			SerialPort[] arrSerialPort = SerialPort.getCommPorts();	
			this.listSerialPorts = this.filterPort(arrSerialPort, "Preon32");
			
			for (SerialPort serialport : this.listSerialPorts) {
				System.out.println(serialport.getSystemPortName());
			}
			
			Preon32Helper nodeHelper = new Preon32Helper("COM6",115200); 
			DataConnection conn = nodeHelper.runModule("SinkNode"); // "bStation

					
			BufferedInputStream in = new BufferedInputStream(conn.getInputStream());

			
			//BufferedOutputStream out = new BufferedOutputStream(conn.getOutputStream());
			int choiceentry;
			
			int input1 = 0;
			int input2 = 0;
			int input4 = 0;
			
			byte[] buffer = new byte[1024];
			//byte[] buffer = new byte[2048];
			String s;
			choice = new Scanner(System.in);
			/// START MENU
			conn.flush();
			do { 
				showMenu(input1, input2, input4);
	    		
		    		choiceentry = choice.nextInt();
		    		conn.write(choiceentry); 
		    		Thread.sleep(1000); 
		    		switch (choiceentry) {
		    			case 1: 
		    				input1 = input1 + 1;
		    				buffer = new byte[1024];
		    				while(in.available() > 0) {
		    					in.read(buffer);
		    					conn.flush();
		    					s = new String(buffer);
		    					String[] subStr = s.split("#");
		    					nodeInfo = new HashMap<>();
		    					
		    					for (String w:subStr) {
		    						w = w.trim();
		    						if (w.isEmpty()) continue;
		    						String[] parts = w.split(" ");
		    						
			    					Long addr = Long.parseLong(parts[0]);
			    					String hex_addr = Long.toHexString(addr);
			    					long time = Long.parseLong(parts[1]);
			    					long rtt = Long.parseLong(parts[2].trim());
			    					boolean isCH = parts[3].equals("CH");
			    					if (isCH) {
			    						clusterMap.putIfAbsent(hex_addr, new ArrayList<>());
			    					}
			    					else {
			    						long chAddr = Long.parseLong(parts[3]);
			    						String chHex_addr = Long.toHexString(chAddr);

			    					    clusterMap.putIfAbsent(chHex_addr, new ArrayList<>());
			    					    if (!clusterMap.get(chHex_addr).contains(hex_addr)) {
			    					        clusterMap.get(chHex_addr).add(hex_addr);
			    					    }
			    					}
			    					String line = hex_addr +
					    					    " Time=" + stringFormatTime.SFFull(time) +
					    					    " RTT=" + rtt + " ms";
			    					
			    					nodeInfo.put(hex_addr, line);
		    					}
		    					
		    					int clusterNum = 1;
		    					for (String ch : clusterMap.keySet()) {
		    					    System.out.println("[Cluster " + clusterNum + "]");

		    					    // print CH
		    					    System.out.println("CH  : " + nodeInfo.get(ch));

		    					    // print CM
		    					    List<String> cmList = clusterMap.get(ch);
		    					    for (String cm : cmList) {
		    					        System.out.println("CM  : " + nodeInfo.get(cm));
		    					    }

		    					    System.out.println();
		    					    clusterNum++;
		    					}
		    				}
		    				break;
					case 2: 
						if (input1 > 0) {
							input2 = input2 + 1;
							buffer = new byte[1024];
							while ( in.available() > 0) { 
								in.read(buffer);
								conn.flush();
								s = new String(buffer);	
								String[] subStr=s.split("#");
								
								nodeInfo = new HashMap<>();
								
								for (String w:subStr) {
									w = w.trim();
									if (w.isEmpty()) continue;
									String[] parts = w.split(" ");
									Long addr = Long.parseLong(parts[0]);
									String hex_addr = Long.toHexString(addr);
									long time = Long.parseLong(parts[1]);
									
									String line = hex_addr +
												" Time=" + stringFormatTime.SFFull(time);
									
									nodeInfo.put(hex_addr, line);
								}
								
								int clusterNum = 1;
			    					for (String ch : clusterMap.keySet()) {
			    					    System.out.println("[Cluster " + clusterNum + "]");
	
			    					    // print CH
			    					    System.out.println("CH  : " + nodeInfo.get(ch));
	
			    					    // print CM
			    					    List<String> cmList = clusterMap.get(ch);
			    					    for (String cm : cmList) {
			    					        System.out.println("CM  : " + nodeInfo.get(cm));
			    					    }
	
			    					    System.out.println();
			    					    clusterNum++;
			    					}
							}
						}
						else {
							System.out.println("Please check for RTT first!");
						}
						break;
					case 3: 
						long t0 = System.currentTimeMillis();
						String deskTime = "DESKTOP TIME NOW: " + stringFormatTime.SFFull(t0);
					    System.out.println(deskTime);
						buffer = new byte[1024];
						while ( in.available() > 0) {
							in.read(buffer);
							conn.flush();
							s = new String(buffer);
							String[] subStr=s.split("#");
							
							nodeInfo = new HashMap<>();
							
							for (String w:subStr) {
								w = w.trim();
		    						if (w.isEmpty()) continue;
		    						String[] parts = w.split(" ");
			    					Long addr = Long.parseLong(parts[0]);
			    					String hex_addr = Long.toHexString(addr);
			    					long time = Long.parseLong(parts[1]);
	
			    					String line = hex_addr +
			    								" Time=" + stringFormatTime.SFFull(time);
			    					
			    					nodeInfo.put(hex_addr, line);
							}
							
							int clusterNum = 1;
		    					for (String ch : clusterMap.keySet()) {
		    					    System.out.println("[Cluster " + clusterNum + "]");
	
		    					    // print CH
		    					    System.out.println("CH  : " + nodeInfo.get(ch));
	
		    					    // print CM
		    					    List<String> cmList = clusterMap.get(ch);
		    					    for (String cm : cmList) {
		    					        System.out.println("CM  : " + nodeInfo.get(cm));
		    					    }
	
		    					    System.out.println();
		    					    clusterNum++;
		    					}
						}
						break;
					case 4: 
						if (input2 > 0) {
							input4 = input4 + 1;
							long msecs = cal.getTimeInMillis();
							String fName = sfTime.SFFile(msecs);
							String folName = sfTime.SFDate(msecs);
							fName = "ACL_"+ fName + ".txt";
							System.out.println("File name: " + fName);
							System.out.println("Folder name: " + folName);
							writeToFile(fName, folName, in); // thread of void
						}
						else {
							System.out.println("Please synchronize time first!");
						}
						break;
					case 5: 
						System.out.println("EXIT PROGRAMME");
						exit = true;
						break;
				}   
	    		
			} while (choiceentry !=5);
			
		} catch (Exception e) { 
			//e.printStackTrace();
		} 
	}
	
	private static DefaultLogger getConsoleLogger() {
	        DefaultLogger consoleLogger = new DefaultLogger();
	        consoleLogger.setErrorPrintStream(System.err);
	        consoleLogger.setOutputPrintStream(System.out);
	        consoleLogger.setMessageOutputLevel(Project.MSG_INFO);
	         
	        return consoleLogger;
	}
	 
	private ArrayList<SerialPort> filterPort(SerialPort[] arr, String target) {
		ArrayList<SerialPort> res = new ArrayList<SerialPort>();
		for (SerialPort serialport : arr) {
			if (serialport.getDescriptivePortName().contains(target)) {
				res.add(serialport);
			}
		}

		return res;
	}
	
	public static void main (String[] args) throws Exception {
		deskApp aGet = new deskApp();
		
		aGet.context_set("context.set.1");
		aGet.time_synchronize();
		aGet.init();
	}
}