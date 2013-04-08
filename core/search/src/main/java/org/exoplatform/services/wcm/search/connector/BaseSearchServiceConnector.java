/*
 * Copyright (C) 2003-2013 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.services.wcm.search.connector;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import org.exoplatform.services.cms.impl.Utils;

import org.exoplatform.commons.api.search.SearchServiceConnector;
import org.exoplatform.commons.api.search.data.SearchContext;
import org.exoplatform.commons.api.search.data.SearchResult;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.cms.drives.DriveData;
import org.exoplatform.services.cms.drives.ManageDriveService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.wcm.core.NodetypeConstant;
import org.exoplatform.services.wcm.search.QueryCriteria;
import org.exoplatform.services.wcm.search.ResultNode;
import org.exoplatform.services.wcm.search.SiteSearchService;
import org.exoplatform.services.wcm.search.base.AbstractPageList;
import org.exoplatform.services.wcm.search.base.EcmsSearchResult;
import org.exoplatform.services.wcm.utils.WCMCoreUtils;

/**
 * This abstract class is extended by the SearchService connectors which provide search result for a specific content type
 */
public abstract class BaseSearchServiceConnector extends SearchServiceConnector {

  public static final String sortByDate = "date";
  public static final String sortByRelevancy = "relevancy";
  public static final String sortByTitle = "title";
  
  protected SiteSearchService siteSearch_;
  protected ManageDriveService driveService_;
  
  protected static final Log LOG = ExoLogger.getLogger(BaseSearchServiceConnector.class.getName());
  
  public static final String DEFAULT_SITENAME = "intranet";
  public static final String PAGE_NAGVIGATION = "documents";
  public static final String NONE_NAGVIGATION = "#";
  public static final String PORTLET_NAME = "FileExplorerPortlet";
  
  public BaseSearchServiceConnector(InitParams initParams) throws Exception {
    super(initParams);
    siteSearch_ = WCMCoreUtils.getService(SiteSearchService.class);
    driveService_ = WCMCoreUtils.getService(ManageDriveService.class);
  }

  /**
   * The connectors must implement this search method, with the following parameters and return a collection of SearchResult
   *
   * @param context Search context
   * @param query The user-input query to search for
   * @param sites Search on these specified sites only (e.g acme, intranet...)
   * @param offset Start offset of the result set
   * @param limit Maximum size of the result set
   * @param sort The field to sort the result set
   * @param order Sort order (ASC, DESC)
   * @return A collection of SearchResult
   */
  @Override
  public Collection<SearchResult> search(SearchContext context,
                                         String query,
                                         Collection<String> sites,
                                         int offset,
                                         int limit,
                                         String sort,
                                         String order) {
    Collection<SearchResult> ret = new ArrayList<SearchResult>();
    //prepare input parameters for search
    QueryCriteria criteria = createQueryCriteria(query, offset, limit, sort, order);
    //query search result
    try {
      if (sites == null || sites.size() == 0 || 
          ConversationState.getCurrent().getIdentity().getUserId() != null) {
        criteria.setSiteName(null);
        ret = convertResult(searchNodes(criteria), limit, context);
      } else if (sites.size() == 1) {
        criteria.setSiteName(sites.iterator().next());
        ret = convertResult(searchNodes(criteria), limit, context);
      } else {//search in many sites
        Iterator<String> iter = sites.iterator();
        criteria.setOffset(0);
        while (iter.hasNext() && limit > 0) {
          criteria.setSiteName(iter.next());
          List<SearchResult> ret1 = convertResult(searchNodes(criteria), limit, context);
          //0 ----- offset ------ offset + limit
          if (ret1.size() <= offset) {
            offset-= ret1.size();
          } else if (ret1.size() < offset + limit) {
            ret.addAll(ret1.subList(offset, ret1.size() - offset));
            limit -= (ret1.size() - offset);
            offset = 0;
          } else {//ret1.size() >= offset + limit
            ret.addAll(ret1.subList(offset, limit - offset));
            offset = 0;
            limit = 0;
          }
        }
      }
    } catch (Exception e) {
      if (LOG.isErrorEnabled()) {
        LOG.error(e);
      }
    }
    return ret;
  }

  /**
   * creates the QueryCriteria object based on the search service
   * @param query the query string
   * @param offset the offset
   * @param limit the limit
   * @param sort sort field
   * @param order order by
   * @return the QueryCriteria 
   */
  protected abstract QueryCriteria createQueryCriteria(String query, long offset, long limit, String sort, String order);
  
  /**
   * searches base on the search service type
   * @param criteria the query criteria
   * @return page list containing the result
   */
  protected abstract AbstractPageList<ResultNode> searchNodes(QueryCriteria criteria) throws Exception;
  
  /**
   * filters the node base on search type: document or file
   * @return the node object
   */
  protected abstract ResultNode filterNode(ResultNode node) throws RepositoryException;
  
  /**
   * converts data: from PageList<ResultNode> to List<SearchResult>
   * @param pageList
   * @return
   */
  protected List<SearchResult> convertResult(AbstractPageList<ResultNode> pageList, int limit, SearchContext context) {
    List<SearchResult> ret = new ArrayList<SearchResult>();
    try {
      if (pageList != null) {
        for (int i = 1; i <= pageList.getAvailablePage(); i++) {
          for (Object obj : pageList.getPage(i)) {
            try {
              if (obj instanceof ResultNode) {
                ResultNode retNode = filterNode((ResultNode)obj);
                if (retNode == null) {
                  continue;
                }
                //generate SearchResult object
                DriveData driveData = getDriveData(retNode);
                Calendar date = getDate(retNode);
                EcmsSearchResult result = 
                //  new SearchResult(url, title, excerpt, detail, imageUrl, date, relevancy);
                    new EcmsSearchResult(getPath(driveData, retNode, context), 
                                         getTitleResult(retNode), 
                                         retNode.getExcerpt(), 
                                         (driveData == null ? "" : driveData.getName()) + fileSize(retNode) + formatDate(date), 
                                         getImageUrl(retNode), 
                                         date.getTimeInMillis(), 
                                         (long)retNode.getScore(),
                                         getFileType(retNode));
                if (result != null) {
                  ret.add(result);
                }
                if (ret.size() >= limit) {
                  return ret;
                }
              }//if
            } catch (Exception e) {
              if (LOG.isErrorEnabled()) {
                LOG.error(e);
              }
            }
          }//for inner
        } //for outer
      }//if
    } catch (Exception e) {
      if (LOG.isErrorEnabled()) {
        LOG.error(e.getMessage());
      }
    }
    return ret;
  }
  
  /**---------------------------HELPER METHODS----------------------------------*/

  /**
   * gets the file size 
   * @param node The node
   * @return the file size
   * @throws Exception
   */
  protected String fileSize(Node node) throws Exception {
    return Utils.fileSize(node);
  }
  
  /**
   * gets the image url
   * @return
   * @throws Exception
   */
  protected String getImageUrl(Node node) {
    return "/eXoResources/skin/images/Icons/FileTypeIcons/uiIconsFileType64x64.png";
  }
  
  /**
   * returns the drive data object which is closest to the node (in term of path)
   * @param node the node
   * @return the drive data
   * @throws Exception
   */
  protected DriveData getDriveData(Node node) throws Exception {
    List<DriveData> dataList = getDriveDataList();
    DriveData ret = null;
    for (DriveData data : dataList) {
      if (node.getPath().startsWith(data.getHomePath())) {
        if (ret == null || ret.getHomePath().length() < data.getHomePath().length()) {
          ret = data;
        }
      }
    }
    return ret;
  }

  /**
   * gets all drive datas which is accessible by current user
   * @return the drive data list
   * @throws Exception
   */
  protected List<DriveData> getDriveDataList() throws Exception {
    return driveService_.getDriveByUserRoles(ConversationState.getCurrent().getIdentity().getUserId(), 
                                             org.exoplatform.services.cms.impl.Utils.getMemberships());
  }
  
  /**
   * returns the date information of node
   * @param node the node
   * @return the date
   * @throws Exception
   */
  protected Calendar getDate(Node node) throws Exception {
    return node.hasProperty(NodetypeConstant.EXO_LAST_MODIFIED_DATE) ? 
                 node.getProperty(NodetypeConstant.EXO_LAST_MODIFIED_DATE).getDate() :
                 node.getProperty(NodetypeConstant.EXO_DATE_CREATED).getDate();
  }

  /**
   * formats the date object in simple date format
   * @param date the Date object
   * @return the String representation
   */
  protected String formatDate(Calendar date) {
    DateFormat format = SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.FULL, SimpleDateFormat.SHORT);
    return " - " + format.format(date.getTime());
  }
  
  /**
   * returns path of node in format: "{drivename}/{relative path from drive root node}
   * @param driveData the drive data object
   * @param node the node
   * @return the expected path
   * @throws Exception
   */
  protected abstract String getPath(DriveData driveData, ResultNode node, SearchContext context) throws Exception;
  
  /**
   * gets the file type
   * @return
   * @throws Exception
   */
  protected abstract String getFileType(ResultNode node) throws Exception;
  
  /**
   * gets the title of result, based on the result type
   * @param node
   * @return
   * @throws Exception
   */
  protected abstract String getTitleResult(ResultNode node) throws Exception;
  
}
