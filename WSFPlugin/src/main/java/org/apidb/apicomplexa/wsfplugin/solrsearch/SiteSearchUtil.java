package org.apidb.apicomplexa.wsfplugin.solrsearch;

import static org.apidb.apicommon.model.TranscriptUtil.getGeneRecordClass;
import static org.apidb.apicommon.model.TranscriptUtil.isTranscriptRecordClass;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status.Family;

import org.apache.log4j.Logger;
import org.gusdb.fgputil.IoUtil;
import org.gusdb.fgputil.json.JsonIterators;
import org.gusdb.fgputil.runtime.GusHome;
import org.gusdb.fgputil.runtime.InstanceManager;
import org.gusdb.wdk.model.Utilities;
import org.gusdb.wdk.model.WdkModel;
import org.gusdb.wdk.model.record.PrimaryKeyDefinition;
import org.gusdb.wdk.model.record.RecordClass;
import org.gusdb.wsf.plugin.PluginModelException;
import org.gusdb.wsf.plugin.PluginRequest;
import org.json.JSONArray;
import org.json.JSONObject;

public class SiteSearchUtil {

  private static final Logger LOG = Logger.getLogger(SiteSearchUtil.class);

  private static final String LOCALHOST_PROP_KEY = "LOCALHOST";
  private static final String SERVICE_URL_PROP_KEY = "SITE_SEARCH_SERVICE_URL";

  private static final String METADATA_URI = "/categories-metadata";

  public static class SearchField {

    public final String _solrField;
    public final String _display;
    public final String _term;

    public SearchField(String name, String display, String term) {
      _solrField = name;
      _display = display;
      _term = term;
    }

    public String getSolrField() {
      return _solrField;
    }
    public String getDisplay() {
      return _display;
    }
    public String getTerm() {
      return _term;
    }

    @Override
    public String toString() {
      return new JSONObject()
        .put("solrField", _solrField)
        .put("display", _display)
        .put("term", _term)
        .toString();
    }
  }

  public static WdkModel getWdkModel(String projectId) {
    return InstanceManager.getInstance(WdkModel.class, GusHome.getGusHome(), projectId);
  }

  public static String getSiteSearchServiceUrl(PluginRequest request) throws PluginModelException {
    Map<String,String> modelProps = getWdkModel(request.getProjectId()).getProperties();
    String localhost = modelProps.get(LOCALHOST_PROP_KEY);
    String siteSearchServiceUrl = modelProps.get(SERVICE_URL_PROP_KEY);
    if (localhost == null || siteSearchServiceUrl == null) {
      throw new PluginModelException("model.prop must contain the properties: " +
          LOCALHOST_PROP_KEY + ", " + SERVICE_URL_PROP_KEY);
    }
    return localhost + siteSearchServiceUrl;
  }

  private static RecordClass getRecordClass(PluginRequest request) throws PluginModelException {
    String questionFullName = request.getContext().get(Utilities.QUERY_CTX_QUESTION);
    return getWdkModel(request.getProjectId()).getQuestionByFullName(questionFullName)
      .map(question -> question.getRecordClass())
      .orElseThrow(() -> new PluginModelException("Could not find context question: " + questionFullName));
  }

  public static String getRequestedDocumentType(PluginRequest request) throws PluginModelException {
    RecordClass recordClass = getRecordClass(request);
    return (isTranscriptRecordClass(recordClass) ?
      getGeneRecordClass(getWdkModel(request.getProjectId())) :
      recordClass
    ).getUrlSegment();
  }

  public static PrimaryKeyDefinition getPrimaryKeyDefinition(PluginRequest request) throws PluginModelException {
    return getRecordClass(request).getPrimaryKeyDefinition();
  }

  public static List<SearchField> getSearchFields(String siteSearchServiceUrl, String documentType) throws PluginModelException {
    Response response = null;
    try {
      Client client = ClientBuilder.newClient();
      String metadataUrl = siteSearchServiceUrl + METADATA_URI;
      LOG.info("Querying site search service with: " + metadataUrl);
      WebTarget webTarget = client.target(metadataUrl);
      Invocation.Builder invocationBuilder = webTarget.request(MediaType.APPLICATION_JSON);
      response = invocationBuilder.get();
      String responseBody = IoUtil.readAllChars(new InputStreamReader((InputStream)response.getEntity()));
      if (!response.getStatusInfo().getFamily().equals(Family.SUCCESSFUL)) {
        throw new PluginModelException("Unable to retrieve metadata from site " +
            "search service.  Request returned " + response.getStatus() +
            ". Response body:\n" + responseBody);
      }
      JSONArray docTypes = new JSONObject(responseBody).getJSONArray("documentTypes");
      List<JSONArray> fieldsJsons = JsonIterators.arrayStream(docTypes)
        .map(obj -> obj.getJSONObject())
        .filter(obj -> obj.getString("id").equals(documentType))
        .map(obj -> obj.getJSONObject("wdkRecordTypeData").getJSONArray("searchFields"))
        .collect(Collectors.toList());
      if (fieldsJsons.size() != 1) {
        throw new PluginModelException("Could not find unique document type with id " + documentType);
      }
      return JsonIterators.arrayStream(fieldsJsons.get(0))
        .map(obj -> obj.getJSONObject())
        .map(json -> new SearchField(
           json.getString("name"),
           json.getString("displayName"),
           json.getString("term")))
        .collect(Collectors.toList());
    }
    catch (IOException e) {
      throw new PluginModelException("Could not read categories-metadata response", e);
    }
    finally {
      if (response != null) response.close();
    }
  }
}
