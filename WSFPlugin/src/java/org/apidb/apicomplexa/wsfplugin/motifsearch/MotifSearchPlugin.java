/**
 * 
 */
package org.apidb.apicomplexa.wsfplugin.motifsearch;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gusdb.wsf.plugin.WsfPlugin;
import org.gusdb.wsf.plugin.WsfServiceException;

/**
 * @author Jerric
 * @created Jan 31, 2006
 */
public class MotifSearchPlugin extends WsfPlugin {

    private class Match {

        public String geneID;
        public String locations;
        public int matchCount = 0;
        public String sequence;
    }

    private static final String PROPERTY_FILE = "motifSearch-config.xml";

    // class string definition
    public static final String CLASS_ACIDIC = "[de]";
    public static final String CLASS_ALCOHOL = "[st]";
    public static final String CLASS_ALIPHATIC = "[ilv]";
    public static final String CLASS_AROMATIC = "[fhwy]";
    public static final String CLASS_BASIC = "[krh]";
    public static final String CLASS_CHARGED = "[dehkr]";
    public static final String CLASS_HYDROPHOBIC = "[avilmfyw]";
    public static final String CLASS_HYDROPHILIC = "[krhdenq]";
    public static final String CLASS_POLAR = "[cdehknqrst]";
    public static final String CLASS_SMALL = "[acdgnpstv]";
    public static final String CLASS_TINY = "[ags]";
    public static final String CLASS_TURNLIKE = "[acdeghknqrst]";

    // required parameter definition
    public static final String PARAM_DATASET = "Dataset";
    public static final String PARAM_EXPRESSION = "Expression";

    public static final String COLUMN_GENE_ID = "GeneID";
    public static final String COLUMN_LOCATIONS = "Locations";
    public static final String COLUMN_MATCH_COUNT = "MatchCount";
    public static final String COLUMN_SEQUENCE = "Sequence";

    // field definition
    private static final String FIELD_DATA_DIR = "DataDir";
    private static final String FIELD_SOURCEID_REGEX = "SourceIdRegex";

    private File dataDir;
    private String sourceIdRegex;

    /**
     * @throws WsfServiceException
     * 
     */
    public MotifSearchPlugin() throws WsfServiceException {
        super(PROPERTY_FILE);
        // load properties
        String dir = getProperty(FIELD_DATA_DIR);
        if (dir == null)
            throw new WsfServiceException(
                    "The required field in property file is missing: "
                            + FIELD_DATA_DIR);
        dataDir = new File(dir);
        sourceIdRegex = getProperty(FIELD_SOURCEID_REGEX);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.gusdb.wsf.WsfPlugin#getRequiredParameters()
     */
    @Override
    protected String[] getRequiredParameterNames() {
        return new String[] { PARAM_EXPRESSION, PARAM_DATASET };
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.gusdb.wsf.WsfPlugin#getColumns()
     */
    @Override
    protected String[] getColumns() {
        return new String[] { COLUMN_GENE_ID, COLUMN_LOCATIONS,
                COLUMN_MATCH_COUNT, COLUMN_SEQUENCE };
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.gusdb.wsf.plugin.WsfPlugin#validateParameters(java.util.Map)
     */
    @Override
    protected void validateParameters(Map<String, String> params)
            throws WsfServiceException {
    // do nothing in this plugin
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.gusdb.wsf.WsfPlugin#execute(java.util.Map, java.lang.String[])
     */
    @Override
    protected String[][] execute(Map<String, String> params,
            String[] orderedColumns) throws WsfServiceException {
        logger.info("Invoking MotifSearchPlugin...");

        // get parameters
        String datasetIDs = params.get(PARAM_DATASET);
        String expression = params.get(PARAM_EXPRESSION);

        // get optional parameters
        String colorCode = "Red";
        if (params.containsKey("ColorCode"))
            colorCode = params.get("ColorCode");
        int contextLength = 0;
        if (params.containsKey("ContextLength"))
            contextLength = Integer.parseInt(params.get("ContextLength"));
        if (contextLength <= 0) contextLength = 20;

        // translate the expression
        String regex = translateExpression(expression);

        // open the flatfile database assigned by the user
        try {
            List<Match> matches = new ArrayList<Match>();
            String[] dsIds = datasetIDs.split(",");

            // scan on each dataset, and add matched motifs in the result
            for (String dsId : dsIds) {
                matches.addAll(findMatches(dsId.trim(), regex, colorCode,
                        contextLength));
            }

            // construct results
            return prepareResult(matches, orderedColumns);
        } catch (IOException ex) {
            throw new WsfServiceException(ex);
        }
    }

    /**
     * Available flatfile databases are listed here:
     * 
     * <"gus_pf_annotpep"> P. falciparum annotated proteins
     * 
     * <"gus_pf_predictpep"> P. falciparum predicted proteins
     * 
     * <"gus_pv_predictpep"> P. vivax predicted proteins
     * 
     * <"gus_py_annotpep"> P. yoelii annotated proteins
     * 
     * <"pf_orfsgt100"> P. falciparum ORFs > 100 AA
     * 
     * <"pf_orfsgt50"> P. falciparum ORFs > 50 AA
     * 
     * <"orfsgt100"> Plasmodium ORFs > 100 AA
     * 
     * <"orfsgt50"> Plasmodium ORFs > 50 AA
     * 
     * <"pb_orfsgt100"> P. berghei ORFs > 100 AA
     * 
     * <"pb_orfsgt50"> P. berghei ORFs > 50 AA
     * 
     * <"pc_orfsgt100"> P. chabaudi ORFs > 100 AA
     * 
     * <"pc_orfsgt50"> P. chabaudi ORFs > 50 AA
     * 
     * <"pg_orfsgt100"> P. gallinaceum ORFs > 100 AA
     * 
     * <"pk_orfsgt100"> P. knowlesi ORFs > 100 AA
     * 
     * <"pk_orfsgt50"> P. knowlesi ORFs > 50 AA
     * 
     * <"pr_orfsgt100"> P. reichenowi ORFs > 100 AA
     * 
     * <"pr_orfsgt50"> P. reichenowi ORFs > 50 AA
     * 
     * <"pv_orfsgt100"> P. vivax ORFs > 100 AA
     * 
     * <"pv_orfsgt50"> P. vivax ORFs > 50 AA
     * 
     * <"py_orfsgt100"> P. yoelii ORFs > 100 AA
     * 
     * <"py_orfsgt50"> P. yoelii ORFs > 50 AA
     * 
     * @param fileID
     * @return
     * @throws IOException
     */
    private File openDataFile(String datasetID) throws IOException {
        File dataFile = new File(dataDir, datasetID);
        if (!dataFile.exists()) throw new IOException("The dataset \""
                + datasetID + "\" cannot be found.");
        else return dataFile;
    }

    private String translateExpression(String expression) {
        // remove spaces
        String regex = expression.replaceAll("\\s", "");

        // replace '(' to '{', ')' to '}', 'x' to '.', '<' to '(', '>' to ')'
        regex = regex.replace('(', '{');
        regex = regex.replace(')', '}');
        regex = regex.replace('x', '.');
        regex = regex.replace('<', '(');
        regex = regex.replace('>', ')');

        // remove '-'
        regex = regex.replaceAll("[\\-]", "");

        // replace numbers/number pairs by surrounding them with "{}"
        Pattern pattern = Pattern.compile("\\(\\d+(,\\d+)?\\)");
        Matcher matcher = pattern.matcher(regex);
        int prev = 0;
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            // get previous part
            sb.append(regex.substring(prev, matcher.start()));
            // replace '(' to '{'
            sb.append('{');
            sb
                    .append(regex.subSequence(matcher.start() + 1, matcher
                            .end() - 1));
            sb.append('}');
            prev = matcher.end();
        }
        // add last part into the buffer
        sb.append(regex.substring(prev));
        regex = sb.toString();

        // replace {string} into ignoring characters
        pattern = Pattern.compile("\\{(\\D+?)\\}");
        matcher = pattern.matcher(regex);
        prev = 0;
        sb.delete(0, sb.length());
        while (matcher.find()) {
            // get the previous part
            sb.append(regex.substring(prev, matcher.start()));
            // replace '{' with "[^"
            sb.append("[^");
            sb.append(regex.substring(matcher.start() + 1, matcher.end() - 1));
            sb.append(']');
            prev = matcher.end();
        }
        // add the last part
        sb.append(regex.substring(prev));
        regex = sb.toString();

        // Split the string by '{.*}'
        String splitStub = "SplitStub";
        regex = regex.replaceAll("\\{", splitStub + "{");
        regex = regex.replaceAll("\\}", "}" + splitStub);
        String[] parts = regex.split(splitStub);
        sb.delete(0, sb.length());
        for (String part : parts) {
            // check if it contains '{'
            if (part.indexOf('{') < 0) { // not containing '{.*}'
                // replace amino acids shortcuts
                part = part.replaceAll("0", CLASS_ACIDIC);
                part = part.replaceAll("1", CLASS_ALCOHOL);
                part = part.replaceAll("2", CLASS_ALIPHATIC);
                part = part.replaceAll("3", CLASS_AROMATIC);
                part = part.replaceAll("4", CLASS_BASIC);
                part = part.replaceAll("5", CLASS_CHARGED);
                part = part.replaceAll("6", CLASS_HYDROPHOBIC);
                part = part.replaceAll("7", CLASS_HYDROPHILIC);
                part = part.replaceAll("8", CLASS_POLAR);
                part = part.replaceAll("9", CLASS_SMALL);
                part = part.replaceAll("B", CLASS_TINY);
                part = part.replaceAll("Z", CLASS_TURNLIKE);
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private List<Match> findMatches(String datasetID, String regex,
            String colorCode, int contextLength) throws IOException {
        File datasetFile = openDataFile(datasetID);
        BufferedReader in = new BufferedReader(new FileReader(datasetFile));

        List<Match> matches = new ArrayList<Match>();
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);

        // read header of the first sequence
        String line;
        line = in.readLine();
        while (true) {
            // get first gene id
            line = line.trim().replaceAll("\\s", " ");
            String geneID = extractField(line, sourceIdRegex);

            StringBuffer seq = new StringBuffer();
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) continue;
                // check if we reach the header of the next sequence
                // if so, we just finishe reading the sequence, exit inner loop
                if (line.charAt(0) == '>') break;
                seq.append(line);
            }
            // check if we reach the end of the file
            if (line == null) break;

            // scan the sequence to find all matched locations
            Match match = findLocations(geneID, pattern, seq.toString(),
                    colorCode, contextLength);
            if (match != null) matches.add(match);
        }
        return matches;
    }

    private Match findLocations(String geneID, Pattern pattern,
            String sequence, String colorCode, int contextLength) {
        Match match = new Match();
        match.geneID = geneID;
        StringBuffer sbLoc = new StringBuffer();
        StringBuffer sbSeq = new StringBuffer();
        int prev = 0;

        Matcher matcher = pattern.matcher(sequence);
        while (matcher.find()) {
            // add locations
            if (sbLoc.length() != 0) sbLoc.append("; ");
            sbLoc.append('(');
            sbLoc.append(matcher.start());
            sbLoc.append(", ");
            sbLoc.append(matcher.end());
            sbLoc.append(')');

            // abtain the context sequence
            if ((matcher.start() - prev) <= (contextLength * 2)) {
                // no need to trim
                sbSeq.append(sequence.substring(prev, matcher.start()));
            } else { // need to trim some
                if (prev != 0)
                    sbSeq
                            .append(sequence.substring(prev, prev
                                    + contextLength));
                sbSeq.append("......");
                sbSeq.append(sequence.substring(
                        matcher.start() - contextLength, matcher.start()));
            }
            sbSeq.append("<font color=\"" + colorCode + "\">");
            sbSeq.append(sequence.substring(matcher.start(), matcher.end()));
            sbSeq.append("</font>");
            prev = matcher.end();
            match.matchCount++;
        }
        if (match.matchCount == 0) return null;

        // grab the last context
        if ((prev + contextLength) < sequence.length()) {
            sbSeq.append(sequence.substring(prev, prev + contextLength));
            sbSeq.append("......");
        } else {
            sbSeq.append(sequence.substring(prev));
        }
        match.locations = sbLoc.toString();
        match.sequence = sbSeq.toString();
        return match;
    }

    private String[][] prepareResult(List<Match> matches, String[] cols) {
        String[][] result = new String[matches.size()][cols.length];
        // create an column order map
        Map<String, Integer> orders = new HashMap<String, Integer>();
        for (int i = 0; i < cols.length; i++)
            orders.put(cols[i], i);

        for (int i = 0; i < matches.size(); i++) {
            Match match = matches.get(i);

            result[i][orders.get(COLUMN_GENE_ID)] = match.geneID;
            result[i][orders.get(COLUMN_LOCATIONS)] = match.locations;
            result[i][orders.get(COLUMN_MATCH_COUNT)] = Integer
                    .toString(match.matchCount);
            result[i][orders.get(COLUMN_SEQUENCE)] = match.sequence;
        }
        return result;
    }

    private String extractField(String defline, String regex) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(defline);
        if (matcher.find()) {
            // the match is located at group 1
            return matcher.group(1);
        } else return null;
    }
}