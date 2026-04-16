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
	
	Preon32Helper c;
	private ArrayList<SerialPort> listSerialPorts;
	
	public void writeToFile(String fName, String folName, BufferedInputStream in) throws Exception {
		new Thread() {	
			byte[] buffer = new byte[256];
			String s; 
			long count=0;
			File newFolder = new File(folName);
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

				                if (msg.startsWith("SENSE")) {
				                    try {
				                        String[] parts = msg.trim().split("\\s+");

				                        int seq  = Integer.parseInt(parts[1]);
				                        int node = Integer.parseInt(parts[2]);

				                        int start = msg.indexOf("[");
				                        int endIdx = msg.indexOf("]");
				                        String accel = msg.substring(start, endIdx + 1);

				                        String line = "Node=" + node +
				                                      " Seq=" + seq +
				                                      " ACCL=" + accel;

				                        writer.write(line);
				                        writer.newLine();
				                        writer.flush();

				                        System.out.println("SAVE: " + line);

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

	public void init() throws Exception 
	{
		stringFormatTime sfTime = new stringFormatTime();
		try 
		{
			SerialPort[] arrSerialPort = SerialPort.getCommPorts();	
			this.listSerialPorts = this.filterPort(arrSerialPort, "Preon32");
			
			for (SerialPort serialport : this.listSerialPorts) {
				System.out.println(serialport.getSystemPortName());
			}
			
			Preon32Helper nodeHelper = new Preon32Helper("COM3",115200); 
			DataConnection conn = nodeHelper.runModule("SinkNode"); // "bStation

					
			BufferedInputStream in = new BufferedInputStream(conn.getInputStream());

			
			//BufferedOutputStream out = new BufferedOutputStream(conn.getOutputStream());
			int choiceentry;
			int[] menuChoice	= {0,0,0,0,0,0};
			byte[] buffer = new byte[1024];
			//byte[] buffer = new byte[2048];
			String s;
			choice = new Scanner(System.in);
			/// START MENU
			conn.flush();
			do { 
				System.out.println("MENU");
				System.out.println("1. Check Online"); // utk mendapatkan rata2 delay RTT
	    			System.out.println("2. Synchronize Time");
		    		System.out.println("3. Get Time from Cluster Member");
		    		System.out.println("4. Start Sensing");
		    		//System.out.println("0. Exit --> pa eli");
		    		System.out.println("5. Stop Sensing");
		    		System.out.println("6. Exit Programme");
		    		System.out.println("Choice: ");
	    		
		    		choiceentry = choice.nextInt();
		    		conn.write(choiceentry); 
		    		Thread.sleep(1000); 
		    		switch (choiceentry) {
		    			case 0:
		    				exit = true;
		    				break;
		    			case 1: 
		    				buffer = new byte[1024];
		    				while(in.available() > 0) {
		    					in.read(buffer);
		    					s = new String(buffer);
		    					//System.out.println(s);
		    					conn.flush();
		    					String[] subStr=s.split("#");
		    					//System.out.println(Arrays.toString(subStr));
		    					
		    					for (String w:subStr) {
		    						//if (w.startsWith("HELLO"))
		    							System.out.println(w);
		    						//else if (w.startsWith("RSSI"))
		    							//System.out.println(w);
		    					}
		    				}
		    				break;
					case 2: 
						buffer = new byte[1024];
						while ( in.available() > 0) { 
							in.read(buffer);
							conn.flush();
							s = new String(buffer);	
							String[] subStr=s.split("#");
							for (String w:subStr) {
		    						if (w.startsWith("SET"))
		    							System.out.println(w);
		    						else if (w.startsWith("RSSI"))
		    							System.out.println(w);
	    						}
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
							for (String w:subStr) {
	    						if (w.startsWith("NOW"))
	    							System.out.println(w);
	    						else if (w.startsWith("RSSI"))
	    							System.out.println(w);
							}
						}
						break;
					case 4: 
						long msecs = cal.getTimeInMillis();
						String fName = sfTime.SFFile(msecs);
						String folName = sfTime.SFDate(msecs);
						fName = "ACL_"+ fName + ".txt";
						System.out.println("File name: " + fName);
						System.out.println("Folder name: " + folName);
						writeToFile(fName, folName, in); // thread of void
						break;
					case 5:
						buffer = new byte[1024];
						while (in.available() > 0) {
							in.read(buffer);
							conn.flush();
							s = new String(buffer);
							String[] subStr=s.split("#");
							for (String w:subStr) {
	    						if (w.startsWith("STOP SENSING"))
	    							System.out.println(w);
	    						else if (w.startsWith("RSSI"))
	    							System.out.println(w);
							}
						}
						break;
					case 6: 
						System.out.println("EXIT PROGRAMME");
						exit = true;
						break;
				}   
	    		
			} while (choiceentry !=0);
			
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