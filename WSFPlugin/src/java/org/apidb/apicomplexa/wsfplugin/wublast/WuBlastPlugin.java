package org.apidb.apicomplexa.wsfplugin.wublast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gusdb.wsf.IWsfPlugin;
import org.gusdb.wsf.WsfServiceException;

/**
 * 
 */

/**
 * @author Jerric
 * @created Nov 2, 2005
 */
public class WuBlastPlugin implements IWsfPlugin {

    // column definitions
    public static final String COLUMN_ID = "Id";
    public static final String COLUMN_HEADER = "Header";
    public static final String COLUMN_FOOTER = "Footer";
    public static final String COLUMN_ROW = "TabularRow";
    public static final String COLUMN_BLOCK = "Alignment";
    public static final String[] COLUMNS = { COLUMN_ID, COLUMN_HEADER,
            COLUMN_FOOTER, COLUMN_ROW, COLUMN_BLOCK };

    // required parameter definitions
    public static final String PARAM_APPLICATION = "Application";
    public static final String PARAM_SEQUENCE = "Sequence";
    public static final String PARAM_DATABASE = "Database";
    public static final String[] REQUIRED_PARAMS = { PARAM_APPLICATION,
            PARAM_DATABASE, PARAM_SEQUENCE };

    private static final boolean DEBUG = true;

    private static final String TEMP_FILE_PREFIX = "wu_tmp";
    /**
     * The root dir for saving temp files. It must be ended with delimiter
     */
    private static Long identifier = new Long(0);

    private static String tempDir;
    private static String appPath;
    private static String dataPath;
    private static long maxTime;

    static {
        // load configurations
        Properties prop = new Properties();
        String root = System.getProperty("webservice.home");
        File rootDir;
        if (root == null) {
            root = System.getProperty("catalina.home");
            rootDir = new File(root, "webapps/axis");
        } else rootDir = new File(root);
        File configFile = new File(rootDir, "WEB-INF/wuBlast-config.xml");

        try {
            // TEST
            if (DEBUG) System.out.println(configFile.getAbsolutePath());

            prop.loadFromXML(new FileInputStream(configFile));
            tempDir = prop.getProperty("TempDir");

            if (DEBUG)
                System.out.println((new File(tempDir).getAbsolutePath()));

            appPath = prop.getProperty("AppPath");
            dataPath = prop.getProperty("DataPath");
            maxTime = Long.parseLong(prop.getProperty("MaxTime"));
        } catch (IOException ex) {
            tempDir = "";
            appPath = "";
            dataPath = "";
            maxTime = 5 * 60 * 1000;
        }

        // at class initialization step, remove all temp files
        File dir = new File(tempDir);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().startsWith(TEMP_FILE_PREFIX)) file.delete();
            }
        }
    }

    /**
     * 
     */
    public WuBlastPlugin() {}

    /*
     * (non-Javadoc)
     * 
     * @see IProcessor#invoke(java.lang.String[], java.lang.String[],
     *      java.lang.String[])
     */
    // the order of wublast command is: blastp/blastn/blastx "database1
    // database2 database3" querySeq.aa -option
    // the first's param is "Application", the second one is "Database", the
    // third one is "Sequence", the others are option
    public String[][] invoke(String[] params, String[] values, String[] cols)
            throws WsfServiceException {
        if (DEBUG)
            System.out.println("WuBlastPlugin - Validating parameters.");

        // validate parameters
        if (!validate(params, values, cols))
            throw new WsfServiceException(
                    "Invalid parameters or column definitions");

        // create a map for parameter key-value pairs, and column name-index
        // pairs
        Map<String, String> parameters = new LinkedHashMap<String, String>();
        for (int i = 0; i < params.length; i++) {
            parameters.put(params[i].trim(), values[i]);
        }
        Map<String, Integer> columns = new HashMap<String, Integer>();
        for (int i = 0; i < cols.length; i++) {
            columns.put(cols[i], i);
        }

        // increment the identifier to make sure the temp file has a unique name
        String prefix;
        synchronized (identifier) {
            File seqFile, outFile;
            do {
                prefix = tempDir + TEMP_FILE_PREFIX + "_" + (identifier++);
                seqFile = new File(prefix + ".in");
                outFile = new File(prefix + ".out");
            } while (seqFile.exists() || outFile.exists());
            try {
                seqFile.createNewFile();
                outFile.createNewFile();
            } catch (IOException ex) {
                if (seqFile.exists()) seqFile.delete();
                throw new WsfServiceException(ex);
            }
        }
        String seqFileName = prefix + ".in";
        String outFileName = prefix + ".out";

        try {
            // now prepare the arguments
            String command = prepareParameters(parameters, seqFileName,
                    outFileName);

            // TEST
            // command = "cmd /C copy ncbi_tmp_0.out temp\\";

            // now invoke the process and block until the process finishes
            if (!invokeProcess(command))
                throw new WsfServiceException(
                        "The execution of process is failed.");

            // if the invocation succeeds, prepare the result; otherwise,
            // prepare results for failure scenario
            String[][] result = prepareResult(columns, outFileName);

            // delete temp file
            File seqFile = new File(seqFileName);
            if (seqFile.exists()) seqFile.delete();
            File outFile = new File(outFileName);
            if (outFile.exists()) outFile.delete();
            return result;
        } catch (IOException ex) {
            // delete temp file
            File seqFile = new File(seqFileName);
            if (seqFile.exists()) seqFile.delete();
            File outFile = new File(outFileName);
            if (outFile.exists()) outFile.delete();

            throw new WsfServiceException(ex);
        }
    }

    private boolean validate(String[] params, String[] values, String[] cols) {
        // param names and values should have the same numbers
        if (params.length != values.length) return false;

        // check if the required parameters present
        for (String param : REQUIRED_PARAMS) {
            boolean exist = false;
            for (String name : params) {
                if (name.equals(param)) {
                    exist = true;
                    break;
                }
            }
            if (!exist) return false;
        }

        // check if the columns match
        if (cols.length != COLUMNS.length) return false;
        for (String col : COLUMNS) {
            boolean exist = false;
            for (String name : cols) {
                if (name.equals(col)) {
                    exist = true;
                    break;
                }
            }
            if (!exist) return false;
        }

        // validation passed
        return true;
    }

    private String prepareParameters(Map<String, String> params,
            String seqFileName, String outFileName) throws IOException {
        // create an input file to store sequence information, in FASTA format
        File seqFile = new File(seqFileName);
        if (seqFile.exists()) seqFile.delete();
        seqFile.createNewFile();

        // get sequence
        String seq = params.get(PARAM_SEQUENCE);

        // output sequence in fasta format, with sequence wrapped for every 60
        // characters
        PrintWriter out = new PrintWriter(new FileWriter(seqFile));
        out.println(">Seq1");
        int pos = 0;
        while (pos < seq.length()) {
            int end = Math.min(pos + 60, seq.length());
            out.println(seq.substring(pos, end));
            pos = end;
        }
        out.flush();
        out.close();

        // now prepare the commandline
        StringBuffer sb = new StringBuffer();
        // append program
        // params.put("-o", outFileName);
        sb.append(appPath + params.get(PARAM_APPLICATION));
        sb.append(" " + dataPath + params.get(PARAM_DATABASE));
        sb.append(" " + seqFileName);

        for (String param : params.keySet()) {
            if (!param.equals(PARAM_APPLICATION)
                    && !param.equals(PARAM_DATABASE)
                    && !param.equals(PARAM_SEQUENCE)) {
                sb.append(" -" + param + " " + params.get(param));
            }
        }
        // sb.append(" >"+outFileName);
        sb.append(" O=" + outFileName);
        return sb.toString();
    }

    //
    // private String getDB(String line) {
    // String[] dbs = line.split(" ");
    // StringBuffer result1 = new StringBuffer("\"");
    // for (int i = 0; i < dbs.length; i++) {
    // result1.append(dataPath + dbs[i] + " ");
    // }
    // String result = result1.toString().trim();
    // result = result + "\"";
    // return result;
    // }

    private boolean invokeProcess(String command) {
        try {
            // invoke the command
            Process process = Runtime.getRuntime().exec(command);
            long start = System.currentTimeMillis();
            // check the exit value of the process; if the process is not
            // finished yet, an IllegalThreadStateException is thrown out
            while (true) {
                try {
                    Thread.sleep(1000);

                    int value = process.exitValue();

                    // TEST
                    if (value != 0) {
                        System.err.println(command);
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(process.getErrorStream()));
                        String line;
                        while ((line = in.readLine()) != null) {
                            System.err.println(line);
                        }
                    }

                    // an exception will be thrown before reaching here if the
                    // process is still running
                    return (value == 0) ? true : false;
                } catch (IllegalThreadStateException ex) {
                    // check if time's up
                    long time = System.currentTimeMillis() - start;
                    if (time > maxTime) {
                        process.destroy();
                        return false;
                    }
                } catch (InterruptedException ex) {
                    // do nothing, keep looping
                }
            }
        } catch (IOException ex) {
            // TODO Auto-generated catch block
            ex.printStackTrace();
            // System.err.println(ex);
            return false;
        }
    }

    private String[][] prepareResult(Map<String, Integer> columns,
            String outFileName) throws IOException {
        // open output file
        File outFile = new File(outFileName);
        BufferedReader in = new BufferedReader(new FileReader(outFile));

        String cr = System.getProperty("line.separator");
        String line;

        // read header part
        StringBuffer header = new StringBuffer();
        do {
            line = in.readLine();
            if (line == null)
                throw new IOException("Invalid BLAST output format");
            header.append(line + cr);
        } while (!line.startsWith("Sequence"));

        // read tabular part, which starts after the second empty line
        line = in.readLine(); // skip an empty line
        Map<String, String> rows = new HashMap<String, String>();
        while ((line = in.readLine()) != null) {
            if (line.trim().length() == 0) break;
            rows.put(extractID(line), line);

        }

        line = in.readLine(); // skip an empty line
        // extract alignment blocks
        List<String[]> blocks = new ArrayList<String[]>();
        StringBuffer block = null;
        String[] alignment = null;
        while ((line = in.readLine()) != null) {
            // reach the footer part
            if (line.trim().startsWith("Parameters")) {
                // output the last block, if have
                if (alignment != null) {
                    alignment[columns.get(COLUMN_BLOCK)] = block.toString();
                    blocks.add(alignment);
                }
                break;
            }

            // reach a new start of alignment block
            if (line.length() > 0 && line.charAt(0) == '>') {
                // output the previous block, if have
                if (alignment != null) {
                    alignment[columns.get(COLUMN_BLOCK)] = block.toString();
                    blocks.add(alignment);
                }
                // create a new alignment and block
                alignment = new String[COLUMNS.length];
                block = new StringBuffer();

                // obtain the ID of it, which is the rest of this line
                alignment[columns.get(COLUMN_ID)] = line.substring(1).trim();
            }
            // add this line to the block
            block.append(line + cr);
        }

        // get the rest as the footer part
        StringBuffer footer = new StringBuffer();
        footer.append(line + cr);
        while ((line = in.readLine()) != null) {
            footer.append(line + cr);
        }

        // now reconstruct the result
        int size = Math.max(1, blocks.size());
        String[][] results = new String[size][COLUMNS.length];
        for (int i = 0; i < blocks.size(); i++) {
            alignment = blocks.get(i);
            // copy ID
            int idIndex = columns.get(COLUMN_ID);
            results[i][idIndex] = alignment[idIndex];
            // copy block
            int blockIndex = columns.get(COLUMN_BLOCK);
            results[i][blockIndex] = alignment[blockIndex];
            // copy tabular row
            int rowIndex = columns.get(COLUMN_ROW);
            for (String id : rows.keySet()) {
                if (alignment[idIndex].startsWith(id)) {
                    results[i][rowIndex] = rows.get(id);
                    break;
                }
            }
        }
        // copy the header and footer
        results[0][columns.get(COLUMN_HEADER)] = header.toString();
        results[size - 1][columns.get(COLUMN_FOOTER)] = footer.toString();

        return results;
    }

    private String extractID(String row) {
        // remove last two pieces
        String[] pieces = tokenize(row);
        /*
         * StringBuffer sb = new StringBuffer(); for (int i = 0; i <
         * pieces.length - 2; i++) { sb.append(pieces[i] + " "); } return
         * sb.toString().trim();
         */
        String ID = pieces[0];
        return ID;
    }

    private String[] tokenize(String line) {
        Pattern pattern = Pattern.compile("\\b[\\w\\.]+\\b");
        Matcher match = pattern.matcher(line);
        List<String> tokens = new ArrayList<String>();
        while (match.find()) {
            String token = line.substring(match.start(), match.end());
            tokens.add(token);
        }
        String[] sArray = new String[tokens.size()];
        tokens.toArray(sArray);
        return sArray;
    }
}
