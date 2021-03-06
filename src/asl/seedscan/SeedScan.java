/*
 * Copyright 2011, United States Geological Survey or
 * third-party contributors as indicated by the @author tags.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/  >.
 *
 */
package asl.seedscan;

import java.util.Set;

import java.io.*;

import java.io.BufferedInputStream;
import java.io.Console;
import java.io.DataInputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Formatter;
import java.util.GregorianCalendar;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;

import asl.logging.*;
import asl.metadata.*;
import asl.metadata.meta_new.*;
import asl.security.*;
import asl.seedscan.config.*;
import asl.seedscan.database.*;
import asl.seedscan.metrics.*;
import asl.util.*;

/**
 * 
 */
public class SeedScan
{
    private static final Logger logger = Logger.getLogger("asl.seedscan.SeedScan");

    private static final String allchanURLstr = "http://wwwasl/uptime/honeywell/gsn_allchan.txt";
    private static URL allchanURL;
    private static Handler consoleHandler;
    private static Handler logDatabaseHandler;
    private static Handler logFileHandler;

    public static void findConsoleHandler()
    {
     // Locate the global logger's ConsoleHandler if it exists
        Logger globalLogger = Logger.getLogger("");
        for (Handler handler: globalLogger.getHandlers()) {
            if (handler instanceof ConsoleHandler) {
                consoleHandler = handler;
                break;
            }
        }

     // Ensure the global logger has an attached ConsoleHandler
     // creating one for it if necessary
        if (consoleHandler == null) {
            consoleHandler = new ConsoleHandler();
            globalLogger.addHandler(consoleHandler);
        }
    }

    public static void main(String args[])
    {
        findConsoleHandler();
        consoleHandler.setLevel(Level.ALL);
        Logger.getLogger("").setLevel(Level.CONFIG);

     // Default locations of config and schema files
        File configFile = new File("config.xml");
        File schemaFile = new File("schemas/SeedScanConfig.xsd");
        boolean parseConfig = true;

        ArrayList<File> schemaFiles = new ArrayList<File>();
        schemaFiles.add(schemaFile);

// ==== Command Line Parsing ====
        Options options = new Options();
        Option opConfigFile = new Option("c", "config-file", true, 
                            "The config file to use for seedscan. XML format according to SeedScanConfig.xsd.");
        Option opSchemaFile = new Option("s", "schema-file", true, 
                            "The schame file which should be used to verify the config file format. ");  

        OptionGroup ogConfig = new OptionGroup();
        ogConfig.addOption(opConfigFile);

        OptionGroup ogSchema = new OptionGroup();
        ogConfig.addOption(opSchemaFile);

        options.addOptionGroup(ogConfig);
        options.addOptionGroup(ogSchema);

        PosixParser optParser = new PosixParser();
        CommandLine cmdLine = null;
        try {
            cmdLine = optParser.parse(options, args, true);
        } catch (org.apache.commons.cli.ParseException e) {
            logger.severe("Error while parsing command-line arguments.");
            System.exit(1);
        }

        Option opt;
        Iterator iter = cmdLine.iterator();
        while (iter.hasNext()) {
            opt = (Option)iter.next();
            if (opt.getOpt().equals("c")) {
                configFile = new File(opt.getValue());
            }
            else if (opt.getOpt().equals("s")) {
                schemaFile = new File(opt.getValue());
            }
        }

// ==== Configuration Read and Parse Actions ====
        ConfigParser parser = new ConfigParser(schemaFiles);
        ConfigT config = parser.parseConfig(configFile);

     // Print out configuration file contents
        Formatter formatter = new Formatter(new StringBuilder(), Locale.US);

     // ===== CONFIG: LOCK FILE =====
        File lockFile = new File(config.getLockfile());
        logger.config("SeedScan lock file is '" +lockFile+ "'");
        LockFile lock = new LockFile(lockFile);
        if (!lock.acquire()) {
            logger.severe("Could not acquire lock.");
            System.exit(1);
        }
        
     // ===== CONFIG: LOGGING =====
     // Set the log levels from the configuration
        LogLevels levels = new LogLevels();
        for (LogLevelT level: config.getLog().getLevels().getLevel()) {
            levels.setLevel(level.getName(), level.getValue().value());
        }

     // Set console logging level
        if (config.getLog().getHandlers().getLogConsole() != null) {
            consoleHandler.setLevel(Level.parse(config.getLog().getHandlers().getLogConsole().getHandlerLevel().value()));
        }

     // Create log file handler if specified
        if (config.getLog().getHandlers().getLogFile() != null) {
            LogFileConfig fileCfg = new LogFileConfig();
            LogFileT file = config.getLog().getHandlers().getLogFile();

            try {
                fileCfg.setDirectory(file.getDirectory());
            } catch (FileNotFoundException ex) {
                logger.severe("Error setting log directory: " + ex.toString());
                System.exit(1);
            } catch (IOException ex) {
                logger.severe("Error setting log directory: " + ex.toString());
                System.exit(1);
            }
            fileCfg.setPrefix(file.getPrefix());
            fileCfg.setSuffix(file.getSuffix());

            logFileHandler = new LogFileHandler(fileCfg);
            logFileHandler.setLevel(Level.parse(file.getHandlerLevel().value()));
            Logger.getLogger("").addHandler(logFileHandler);
        }

     // Create log database handler if specified
        if (config.getLog().getHandlers().getLogDb() != null) {
            LogDatabaseConfig dbCfg = new LogDatabaseConfig();
            DatabaseT db = config.getLog().getHandlers().getLogDb().getDatabase();
            dbCfg.setURI(db.getUri());
            dbCfg.setUsername(db.getUsername());
            Password pwd;
            if (db.getPassword().getPlain() == null) {
                pwd = null;
                /*
                EncryptedT enc = db.getPassword.getEncrypted();
                EncryptedPassword epwd = new EncryptedPassword(enc.getIv(), enc.getCipherText(), enc.getHmac()); 
                // Generate key from enc.getSalt() + supplied password
                // epwd.setKey(generatedKey);
                pwd = epwd;
                */
            }
            else {
                TextPassword ppwd = new TextPassword(db.getPassword().getPlain()); 
                pwd = ppwd;
            }
            dbCfg.setPassword(pwd);
            logDatabaseHandler = new LogDatabaseHandler(dbCfg);
            logDatabaseHandler.setLevel(Level.parse(config.getLog().getHandlers().getLogDb().getHandlerLevel().value()));
            Logger.getLogger("").addHandler(logDatabaseHandler);
        }

     // ===== CONFIG: DATABASE =====
        MetricDatabase readDB = new MetricDatabase(config.getDatabase());
        MetricDatabase writeDB = new MetricDatabase(config.getDatabase());
    	MetricReader reader 	= new MetricReader(readDB); // Should this be a separate connection?
    	MetricInjector injector = new MetricInjector(writeDB);


     // ===== CONFIG: SCANS =====
        Hashtable<String, Scan> scans = new Hashtable<String, Scan>();
        if (config.getScans().getScan() == null) {
            logger.severe("No scans in configuration.");
            System.exit(1);
        }
        else {
            for (ScanT scanCfg: config.getScans().getScan()) {
                String name = scanCfg.getName();
                if (scans.containsKey(name)) {
                    logger.severe("Duplicate scan name '" +name+ "' encountered.");
                    System.exit(1);
                }

            // This should really be handled by jaxb by setting it up in schemas/SeedScanConfig.xsd
                if(scanCfg.getStartDay() == null && scanCfg.getStartDate() == null) {
                    System.out.format("== SeedScan Error: Must set EITHER cfg:start_day -OR- cfg:start_date in config.xml to start Scan!");
                    System.exit(0);
                }


                Scan scan = new Scan();
                scan.setPathPattern(scanCfg.getPath());
                scan.setDatalessDir(scanCfg.getDatalessDir());
                scan.setEventsDir(scanCfg.getEventsDir());
                scan.setDaysToScan(scanCfg.getDaysToScan().intValue());
                if (scanCfg.getStartDay() != null) {
                    scan.setStartDay(scanCfg.getStartDay().intValue());
                }
                if (scanCfg.getStartDate() != null) {
                    scan.setStartDate(scanCfg.getStartDate().intValue());
                }

                for (MetricT met: scanCfg.getMetrics().getMetric()) {
                    try {
                        Class metricClass = Class.forName(met.getClassName());
                        MetricWrapper wrapper = new MetricWrapper(metricClass);
                        for (ArgumentT arg: met.getArgument()) {
//System.out.format("== SeedScan: wrapper.add(name=%s, value=%s)\n", arg.getName(), arg.getValue() );
                            wrapper.add(arg.getName(), arg.getValue());
                        }
                        scan.addMetric(wrapper);
                    } catch (ClassNotFoundException ex) {
                        logger.severe("No such metric class '" +met.getClassName()+ "'");
                        System.exit(1);
                    } catch (InstantiationException ex) {
                        logger.severe("Could not dynamically instantiate class '" +met.getClassName()+ "'");
                        System.exit(1);
                    } catch (IllegalAccessException ex) {
                        logger.severe("Illegal access while loading class '" +met.getClassName()+ "'");
                        System.exit(1);
                    } catch (NoSuchFieldException ex) {
                        logger.severe("Invalid dynamic argument to Metric subclass '" +met.getClassName()+ "'");
                        System.exit(1);
                    }

                }

                scans.put(name, scan);
            }
        }


// ==== Establish Database Connection ====
        // TODO: State Tracking in the Database
        // - Record scan started in database.
        // - Track our progress as we go so a new process can pick up where
        //   we left off if our process dies.
        // - Mark when each date-station-channel-operation is complete
        //LogDatabaseHandler logDB = new LogDatabaseHandler(configuration.get

        logger.fine("Testing Logger Level FINE");
        logger.finer("Testing Logger Level FINER");
        logger.finest("Testing Logger Level FINEST");

        // Get a list of stations

        // Get a list of files  (do we want channels too?)

        // For each day ((yesterday - scanDepth) to yesterday)
        // scan for these channel files, only process them if
        // they have not yet been scanned, or if changes have
        // occurred to the file since its last scan. Do this for
        // each scan type. Do not re-scan data for each type,
        // launch processes for each scan and use the same data set
        // for each. If we can pipe the data as it is read, do so.
        // If we need to push all of it at once, do these in sequence
        // in order to preserve overall system memory resources.

        Scan scan = null;
        /*
        if (scans.containsKey()) {
            scan = 
        }
        else {
        }
        */

// ==== Perform Scans ====
        //System.out.format("\n\n==SeedScan: Load StationList and Begin Scan\n\n");

        //Runtime runtime = Runtime.getRuntime();
        //System.out.println(" Java total memory=" + runtime.totalMemory() );

        for (String key : scans.keySet() ) {
           scan = scans.get(key);
           System.out.format("== SeedScan Scan=[%s] startDay=%d startDate=%d daysToScan=%d\n", key, scan.getStartDay(), scan.getStartDate(), scan.getDaysToScan() );
        }

        scan = scans.get("daily");
        MetaGenerator metaGen = new MetaGenerator(scan.getDatalessDir());

 // Really the scan for each station will be handled by ScanManager using thread pools
 // For now we're just going to do it here:

// Set getStationList = false if you want to manually control the StationList below ...
        boolean getStationList = true;
        //getStationList = false;
        //ArrayList<Station> stations;
        List<Station> stations;

        if (getStationList){
            String datalessDir = scan.getDatalessDir();
            stations = getStationList(datalessDir);
        }
        else {
            stations = new ArrayList<Station>();
            //stations.add( new Station("IC","KMI") );
            stations.add( new Station("IU","ANMO") );
            //stations.add( new Station("NE","PQI") );
            //stations.add( new Station("IU","LVC") );
            //stations.add( new Station("IC","BJT") );
        }

        if (stations == null) {
            logger.severe("ERROR: Found NO stations to scan --> EXITTING SeedScan");
            System.exit(0);
        }

        for (Station station : stations){
            System.out.format("== SeedScan: Scan station:[%s]\n", station);
        }

        Thread readerThread = new Thread(reader);
        readerThread.start();
        logger.info("Reader thread started.");
        
        Thread injectorThread = new Thread(injector);
        injectorThread.start();
        logger.info("Injector thread started.");
        
        logger.info("Processing stations...");
        for (Station station: stations) {
            //Scanner scanner = new Scanner(reader, injector, station, scan);
            Scanner scanner = new Scanner(reader, injector, station, scan, metaGen);
            scanner.scan();
        }
        
        try {
	        injector.halt();
	        logger.info("All stations processed. Waiting for injector thread to finish...");
            synchronized(injectorThread) {
	            //injectorThread.wait();
	            injectorThread.interrupt();
            }
	        logger.info("Injector thread halted.");
        } catch (InterruptedException ex) {
        	logger.warning("The injector thread was interrupted while attempting to complete requests.");
        }
        
        try {
	        reader.halt();
	        logger.info("All stations processed. Waiting for reader thread to finish...");
            synchronized(readerThread) {
	            //readerThread.wait();
	            readerThread.interrupt();
            }
	        logger.info("Reader thread halted.");
        } catch (InterruptedException ex) {
        	logger.warning("The reader thread was interrupted while attempting to complete requests.");
        }

        ////Scanner scanner = new Scanner(database,station,scan);
        //scanner.scan();
/**
        ScanManager manager = new ScanManager(scan);
        manager.run();
**/

        try {
            lock.release();
        } catch (IOException e) {
            ;
        } finally {
            lock = null;
        }
    } // main()


/** getStationList()
  * Scan directory (=path) for dataless files of the form: DATALESS.IU_ANMO.seed
  * If found, add to ArrayList<Station> stations
  * return null if no matching dataless files found
 */ 

    private static List<Station> getStationList( String path ){
        File dir = new File(path);
        if (!dir.exists()) {
            logger.severe("Path '" +dir+ "' does not exist.");
            System.exit(0);
        }
        else if (!dir.isDirectory()) {
            logger.severe("Path '" +dir+ "' is not a directory.");
            System.exit(0);
        }

        FilenameFilter textFilter = new FilenameFilter() {
          public boolean accept(File dir, String name) {
              if ( name.endsWith(".dataless") && (name.length() == 11) ) {
                  return true;
              } else {
                  return false;
              }
          }
        };

        ArrayList<Station> stations = null;
        String[] tmpStringBuf = null;
        String[] files = dir.list(textFilter);

        for (int i=0; i<files.length; i++){
            String knet = null;
            //  files[i]=IU.dataless
            tmpStringBuf  = files[i].split("\\.");
            if (tmpStringBuf.length == 2){
                knet  = tmpStringBuf[0];
            }
            String fileName = dir + "/" + files[i];
            //System.out.format("== Got Dataless file=[%s]\n", fileName);

            ProcessBuilder pb = new ProcessBuilder("rdseed", "-c", "-f", fileName);
            try {
                Process process = pb.start();
                BufferedReader reader = new BufferedReader( new InputStreamReader(process.getInputStream() ) );
                String line = null;
                while( ( line = reader.readLine() ) != null ) {
                    if ( line.startsWith("B011F04-05") ) {
                        tmpStringBuf  = line.split("\\s+");
                        String kstn   = tmpStringBuf[1];
                        if (stations == null) stations = new ArrayList<Station>();
                        stations.add( new Station(knet, kstn) );
                        //System.out.format("     Add Station:[%s_%s]\n", knet, kstn);
                    }
                }
                int shellExitStatus = process.waitFor();
            }
        // Need to catch both IOException and InterruptedException
            catch (IOException e) {
                System.out.println("Error: IOException Description: " + e.getMessage());
            }
            catch (InterruptedException e) {
                System.out.println("Error: InterruptedException Description: " + e.getMessage());
            }

        }
        return stations;
    }



} // class SeedScan
