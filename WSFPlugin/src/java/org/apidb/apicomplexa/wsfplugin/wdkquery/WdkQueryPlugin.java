/**
 *Version 2.0.0 --
 * Updated to work with the new Wdk Model.  The loading subroutine was updated to call parse() correctly for the new code in teh WDK
 * 2/27/2008 -- Removed valiadtion of the columns and inserted code to insert "N/A" into the result is a column does not exists on a component site
 * 6/2/2010  -- if a querySet such as SharedVQ is received, and not found, the site will check sharedParams paramSet.
 *              This will allow to access enums from a flatvocab in portal (only way to access a site) --cris
 */
package org.apidb.apicomplexa.wsfplugin.wdkquery;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.gusdb.wdk.controller.CConstants;
import org.gusdb.wdk.model.Utilities;
import org.gusdb.wdk.model.WdkModel;
import org.gusdb.wdk.model.WdkModelException;
import org.gusdb.wdk.model.dbms.ResultList;
import org.gusdb.wdk.model.jspwrap.EnumParamBean;
import org.gusdb.wdk.model.jspwrap.WdkModelBean;
import org.gusdb.wdk.model.query.Query;
import org.gusdb.wdk.model.query.QueryInstance;
import org.gusdb.wdk.model.query.param.AbstractEnumParam;
import org.gusdb.wdk.model.query.param.FlatVocabParam;
import org.gusdb.wdk.model.query.param.Param;
import org.gusdb.wdk.model.query.param.StringParam;
import org.gusdb.wdk.model.question.Question;
import org.gusdb.wdk.model.user.User;
import org.gusdb.wsf.plugin.AbstractPlugin;
import org.gusdb.wsf.plugin.PluginRequest;
import org.gusdb.wsf.plugin.PluginResponse;
import org.gusdb.wsf.plugin.WsfPluginException;

/**
 * @author Cary Pennington
 * @created Dec 20, 2006
 * 
 *          2.0.0 -- Worked with ApiFedPlugin 2.0.0 2.1 -- Ditched the three
 *          number versioning... not that many changes -- Added support for
 *          accessing Enum Parameters on the componet Sites
 */
public class WdkQueryPlugin extends AbstractPlugin {

  // Propert values

  public static final String VERSION = "3.0";
  // Input Parameters
  public static final String PARAM_PARAMETERS = "Parameters";
  public static final String PARAM_COLUMNS = "Columns";

  public static final int STATUS_ERROR_NO_QUERY = -2;
  public static final int STATUS_ERROR_MODEL_EXCEPTION = -1;
  public static final int STATUS_ERROR_SERVICE_UNAVAILABLE = -3;

  // Member Variables
  private WdkModel wdkModel;

  public WdkQueryPlugin() {
    super();
  }

  // load properties

  /*
   * (non-Javadoc)
   * 
   * @see org.gusdb.wsf.plugin.AbstractPlugin#initialize(java.util.Map)
   */
  @Override
  public void initialize(Map<String, Object> context)
      throws WsfPluginException {
    super.initialize(context);

    WdkModelBean wdkModelBean = (WdkModelBean)context.get(CConstants.WDK_MODEL_KEY);
    wdkModel = wdkModelBean.getModel();

    // logger.info("------------Plugin Initialized-----------------");
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.gusdb.wsf.WsfPlugin#getRequiredParameters()
   */
  @Override
  public String[] getRequiredParameterNames() {
    return new String[] {};
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.gusdb.wsf.WsfPlugin#getColumns()
   */
  @Override
  public String[] getColumns() {
    return new String[] {};
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.gusdb.wsf.plugin.WsfPlugin#validateParameters(java.util.Map)
   */
  @Override
  public void validateParameters(PluginRequest request)
      throws WsfPluginException {
    // do nothing in this plugin
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.gusdb.wsf.WsfPlugin#execute(java.util.Map, java.lang.String[])
   */
  @Override
  public void execute(PluginRequest request, PluginResponse response)
      throws WsfPluginException {

    logger.info("WdkQueryPlugin Version : " + WdkQueryPlugin.VERSION);
    // logger.info("Invoking WdkQueryPlugin......");
    int resultSize = 0;
    ResultList resultList = null;
    Map<String, String> paramValues = request.getParams();
    Map<String, String> context = request.getContext();

    // when running a param search, the question should hold the question
    // that has the query to which the param belongs; the param should hold
    // the flatVocab param name; the query is the name of the vocab query.

    // when running a id search, the question holds the name of the search,
    // the param should be empty, and the query holds the name of the id
    // query.
    String questionName = context.get(Utilities.QUERY_CTX_QUESTION);
    String paramName = context.get(Utilities.QUERY_CTX_PARAM);
    String queryName = context.get(Utilities.QUERY_CTX_QUERY);

    if (paramValues.containsKey("Query"))
      paramValues.remove("Query");
    // logger.info("QueryName = "+ invokeKey);

    // Map<String,Object>SOParams = convertParams(params);
    // logger.info("Parameters were processed");

    logger.debug("context question: '" + questionName + "', param: '"
        + paramName + "', query: '" + queryName + "', SiteModel=" + wdkModel.getProjectId());

    // Variable to maintain the order of columns in the result... maintains
    // order given by Federation Plugin
    String[] orderedColumns = request.getOrderedColumns();
    Map<String, Integer> columnOrders = new LinkedHashMap<>(
        orderedColumns.length);
    for (int i = 0; i < orderedColumns.length; i++) {
      columnOrders.put(orderedColumns[i], i);
    }

    try {
      // web service call to get param values
      if (paramName != null) {
        resultSize = writeParamResult(response, paramValues, columnOrders,
            questionName, paramName);
        logger.info("Param results have been processed.... " + resultSize);
        return;
      }

      // check if question is set
      Query query;
      if (questionName != null) {
        Question question = wdkModel.getQuestion(questionName);
        query = question.getQuery();
      } else {
        query = (Query) wdkModel.resolveReference(queryName);
      }

      // get the user
      String userSignature = context.get(Utilities.QUERY_CTX_USER);
      User user = wdkModel.getUserFactory().getUser(userSignature);

      // converting from internal values to dependent values
      Map<String, String> SOParams = convertParams(user, paramValues,
          query.getParamMap());// getParamsFromQuery(q));

      // execute query, and get results back
      logger.info("Processing Query " + query.getFullName() + "...");
      QueryInstance queryInstance = query.makeInstance(user, SOParams, true, 0,
          context);
      resultList = queryInstance.getResults();
      logger.info("Results set was filled");

      resultSize = writeQueryResults(response, resultList, columnOrders);
      logger.info("Query results have been processed.... " + resultSize);

    } catch (WdkModelException ex) {
      logger.info("WdkMODELexception in execute()" + ex.toString());
      // String msg = ex.toString();
      String msg = ex.formatErrors();
      logger.info("Message = " + msg);
      // if(msg.matches("Invalid value"){}
      if (msg.indexOf("Please choose value(s) for parameter") != -1) {
        resultSize = 0;
      } else if (msg.contains("No value supplied for param")) {
        resultSize = 0;
        // isolate query on crypto/plasmo with only param values for
        // plasmo
      } else if (msg.contains("does not exist")) {
        resultSize = 0;
      } else if (msg.indexOf("does not contain") != -1) {
        resultSize = -2; // query set or query doesn't exist
        // } else if (msg.indexOf("encountered an invalid term") != -1) {
        // resultSize = 0; // parameter value relates to a different comp site
      } else if (msg.indexOf("does not include") != -1) {
        resultSize = -2; // query set or query doesn't exist
      } else if (msg.contains("datasets value '' has an error: Missing the value")) {
        resultSize = 0;
      } else if (msg.contains("Invalid term")) {
        resultSize = 0;
			} else if (msg.contains("Some of the input parameters are invalid")) {
					resultSize = 0;
      } else {
        logger.error("WdkModelException: " + ex);
        resultSize = -1; // actual error, can't handle
      }
    } catch (Exception ex) {
      logger.error("OTHERexception IN execute()", ex);

      resultSize = -1;
    }

    response.setMessage(Integer.toString(resultSize));
    response.setSignal(resultSize);
  }

  private int writeParamResult(PluginResponse response,
      Map<String, String> paramValues, Map<String, Integer> columnOrders,
      String questionName, String paramName) throws WdkModelException,
      WsfPluginException {
    // get param
    Param param;
    if (questionName != null) {
      // context question is defined, should get the param from
      // question
      Question question = wdkModel.getQuestion(questionName);
      String partName = paramName.substring(paramName.indexOf(".") + 1);
      param = question.getParamMap().get(partName);

      logger.debug("got param from question: " + (param != null));

      // param doesn't exist in the context question, try to get
      // it from model.
      if (param == null)
        // param = (Param) wdkModel.resolveReference(paramName);
        throw new WdkModelException("parameter " + paramName
            + " does not exist in question " + questionName);
    } else {
      logger.debug("got param from model.");

      // context question is not defined, get original param from
      // model.
      param = (Param) wdkModel.resolveReference(paramName);
    }
    logger.debug("Parameter found : " + param.getFullName());
    if (param instanceof FlatVocabParam)
      logger.debug("param query: "
          + ((FlatVocabParam) param).getQuery().getFullName());

    // only process the result if it's an enum param
    if (param instanceof AbstractEnumParam) {
      return handleVocabParams(response, (AbstractEnumParam) param,
          paramValues, columnOrders);
    } else
      return 0;
  }

  private int writeQueryResults(PluginResponse response, ResultList results,
      Map<String, Integer> columnOrders) throws WdkModelException,
      WsfPluginException {
    int resultSize = 0;
    while (results.next()) {
      String[] row = new String[columnOrders.size()];
      for (String column : columnOrders.keySet()) {
        // read the value for the column, and get the string representation
        Object obj = results.get(column);
        String val = null;
        if (obj == null)
          val = "N/A";
        else if (obj instanceof String)
          val = (String) obj;
        else if (obj instanceof char[])
          val = new String((char[]) obj);
        else if (obj instanceof byte[])
          val = new String((byte[]) obj);
        else
          val = obj.toString();

        // assign the column to the proper place
        row[columnOrders.get(column)] = val;
      }

      // save row to repsonse
      response.addRow(row);
      resultSize++;
    }
    results.close();
    return resultSize;
  }

  private Map<String, String> convertParams(User user,
      Map<String, String> paramValues, Map<String, Param> params)
      throws WdkModelException {
    Map<String, String> ret = new HashMap<String, String>();
    for (String key : paramValues.keySet()) {
      String value = paramValues.get(key);
      if (params.containsKey(key)) {
        Param param = params.get(key);
        if (param instanceof AbstractEnumParam) {
          String valList = value;
          AbstractEnumParam abParam = (AbstractEnumParam) param;
          EnumParamBean abParamBean = new EnumParamBean(abParam);
          if (abParam.isDependentParam()) {
            Map<String, String> dependedValues = new LinkedHashMap<>();
            for (Param dependedParam : abParam.getDependedParams()) {
              String dependedParamValue = paramValues.get(dependedParam.getName());
              dependedValues.put(dependedParam.getName(), dependedParamValue);
            }
            abParamBean.setDependedValues(dependedValues);
          }
          if ((param instanceof FlatVocabParam || param.isAllowEmpty())
              && valList.length() == 0) {
            try {
              valList = abParamBean.getDefault();
            } catch (Exception e) {
              logger.info("error using default value.");
            }
          }

          // Code to specifically work around a specific problem
          // created by the OrthologPattern Question
          if (param.getName().equalsIgnoreCase("phyletic_indent_map"))
            valList = "ARCH";
          if (param.getName().equalsIgnoreCase("phyletic_term_map"))
            valList = "rnor";
          // end workaround

          String[] vals;
          Boolean multipick = abParamBean.getMultiPick();
          if (multipick) {
            vals = valList.split(",");
          } else {
            vals = new String[1];
            vals[0] = valList;
          }
          String newVals = "";
          for (String mystring : vals) {
            // unescape each individual term.
            mystring = unescapeValue(mystring, abParamBean.getQuote());
            try {
              logger.info("ParamName = " + param.getName() + " ------ Value = "
                  + mystring);
              if (validateSingleValues(abParamBean, mystring.trim())) {
                // ret.put(param.getName(), o);
                newVals = newVals + "," + mystring.trim();
                logger.info("validated-------------\n ParamName = "
                    + param.getName() + " ------ Value = " + mystring);
              } else {
                logger.warn("param validation failed: " + "param="
                    + param.getName() + ", value='" + mystring + "'");
              }
            } catch (Exception e) {
              logger.info(e);
            }
          }

          if (newVals.length() != 0)
            newVals = newVals.substring(1);
          else
            newVals = "\u0000";
          logger.info("validated values string -------------" + newVals);
          value = newVals;
        } else { // other types, unescape the whole thing
          boolean quoted = true;
          if (param instanceof StringParam)
            quoted = !((StringParam) param).isNumber();
          value = unescapeValue(value, quoted);
        }
        ret.put(param.getName(), value);
      }
    }
    return ret;
  }

  private boolean validateSingleValues(EnumParamBean p, String value) {
    String[] conVocab = p.getVocab();
    logger.info("conVocab.length = " + conVocab.length);
    if (p.isSkipValidation())
      return true;
    // initVocabMap();
    for (String v : conVocab) {
      logger.info("value: " + value + " | vocabTerm: " + v);
      if (value.equalsIgnoreCase(v))
        return true;
    }
    return false;
  }

  private int handleVocabParams(PluginResponse response,
      AbstractEnumParam vocabParam, Map<String, String> ps,
      Map<String, Integer> columnOrders) throws WdkModelException,
      WsfPluginException {
    logger.debug("Function to Handle a vocab param in WdkQueryPlugin: "
        + vocabParam.getFullName());
    EnumParamBean paramBean = new EnumParamBean(vocabParam);
    // set depended value if needed
    if (vocabParam.isDependentParam()) {
      Map<String, String> dependedValues = new LinkedHashMap<>();
      for (Param dependedParam : vocabParam.getDependedParams()) {
        String dependedValue = ps.get(dependedParam.getName());
        dependedValues.put(dependedParam.getName(), dependedValue);
        logger.debug(dependedParam.getName() + " ==== " + dependedValue);
      }
      paramBean.setDependedValues(dependedValues);
    }
    Map<String, String> displayMap = paramBean.getDisplayMap();
    Map<String, String> parentMap = paramBean.getParentMap();

    boolean hasTerm = false, hasInternal = false;
    int count = 0;
    for (String term : displayMap.keySet()) {
      String[] row = new String[columnOrders.size()];
      for (String column : columnOrders.keySet()) {
        int index = columnOrders.get(column);
        if (column.equals(FlatVocabParam.COLUMN_TERM)) {
          row[index] = term;
          hasTerm = true;
        } else if (column.equals(FlatVocabParam.COLUMN_DISPLAY)) {
          row[index] = displayMap.get(term);
        } else if (column.equals(FlatVocabParam.COLUMN_INTERNAL)) {
          // always return term as internal in this plugin.
          row[index] = term;
          hasInternal = true;
        } else if (column.equals(FlatVocabParam.COLUMN_PARENT_TERM)) {
          row[index] = parentMap.get(term);
        } else {
          throw new WsfPluginException("Unsupported column: " + column);
        }
      }
      response.addRow(row);
      count++;
    }

    // term & internal has to exist
    if (!hasTerm || !hasInternal)
      throw new WdkModelException("The wsf call for param "
          + paramBean.getFullName()
          + " doesn't specify term & internal columns.");
    return count;
  }

  @Override
  protected String[] defineContextKeys() {
    return new String[]{CConstants.WDK_MODEL_KEY};
  }

  private String unescapeValue(String value, boolean quoted) {
    if (value == null || value.length() == 0)
      return value;

    // will first remove the wrapping quote
    if (quoted) {
      if (value.charAt(0) == '\'')
        value = value.substring(1);
      if (value.charAt(value.length() - 1) == '\'')
        value = value.substring(0, value.length() - 1);
    }

    // then replace double single-quotes to a single quote
    value = value.replace("''", "'");

    return value;
  }
}
