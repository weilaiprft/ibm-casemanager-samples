package com.ibm.ecm.extension.icmsample.boxeventhandler;

import java.util.Properties;
import java.util.logging.Logger;
import java.util.logging.Level;

import com.box.sdk.BoxEvent;
import com.box.sdk.BoxEvent.Type;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.filenet.api.constants.PropertyNames;
import com.filenet.api.core.Factory;
import com.filenet.api.core.Folder;
import com.filenet.api.property.PropertyFilter;
import com.filenet.api.util.Id;
import com.filenet.api.util.UserContext;
import com.ibm.casemgmt.api.box.BoxConstants;
import com.ibm.casemgmt.api.objectref.ObjectStoreReference;
import com.ibm.casemgmt.intgimpl.CEConstants;
import com.ibm.casemgmt.intgimpl.messages.Message;
import com.ibm.ecm.icm.boxeventhandlers.BaseHandler;
import com.ibm.ecm.icm.util.P8ConnectionUtil;
import com.ibm.icm.edc.api.Constants;
import com.ibm.icm.edc.api.DefaultServiceManagerFactory;
import com.ibm.icm.edc.api.ServiceManager;
import com.ibm.icm.edc.api.TaskService;
import com.ibm.json.java.JSONObject;

/**
 * This is a sample Box Event Handler that watches the Box Document Upload event and copies 
 * the Box document to the Case if the document was uploaded in a Box sub-folder you specify from the UI.
 */
public class MonitorBoxDocumentUpload extends BaseHandler {
	protected String p8DocType;
	protected Boolean bFileDocToCase;
	protected String solutionName;
	protected String folderName;
	
	private static final String CLASS_NAME = "MonitorBoxDocumentUpload";

	/**
	 * Constructs the MonitorBoxDocumentUpload with targetRepostiory, logger and parameters for the event handler.
	 * @param targetRepositoryId
	 * @param handlerLogger
	 * @param handlerParameters
	 */
	public MonitorBoxDocumentUpload(String targetRepositoryId, Logger handlerLogger, JSONObject handlerParameters) {
		super(targetRepositoryId, handlerLogger, handlerParameters);
		this.p8DocType = (String) handlerParameters.get(EventHandlerConstants.JSON_DOCUMENT_CLASS);
		this.bFileDocToCase = (Boolean) handlerParameters.get(EventHandlerConstants.JSON_FILE_DOC_TO_CASE);
		this.solutionName = (String) handlerParameters.get(EventHandlerConstants.JSON_SOLUTION);
		this.folderName = (String) handlerParameters.get(EventHandlerConstants.JSON_FOLDER_NAME);
	}
	
	@Override
	/**
	 * The custom event handler name.
	 * @return eventHandlerName The custom event handler name.
	 */
	public String getName() {
		String eventHandlerName = "Monitor Box Document Upload Event Handler";
		return eventHandlerName;
	}
	
	/**
	 * Listens for all Box document uploads that occur in the specified folder name from the event handler 
	 * configuration dialog. If the conditions do not match, then the event does not get processed.
	 * 
	 * @param event A Box event
	 * @return isSupported A boolean indicating whether you want to process the caught Box event 
	 */
	@Override
	public boolean eventSupported(BoxEvent event) {
		boolean isSupported = false;
		Type supportedType = Type.ITEM_UPLOAD;
		try {
			JsonObject boxEventObject = event.getSourceJSON();
			JsonValue documentParentFolder = boxEventObject.get("parent");
			String documentParentFolderName = ((JsonObject) documentParentFolder).getString(BoxConstants.JSON_NAME, "");
			
			// Event is supported if the event is of ITEM_UPLOAD type and the parent folder that the document was uploaded 
			// into matches the folder name specified in the parameters of the dialog.
			if ((event.getType() == supportedType) && this.folderName.equalsIgnoreCase(documentParentFolderName)) {
				isSupported = true;
			} else {
				isSupported = false;
			}
		} catch(Exception ex) {
			String logMsg = "Something unexpected happened with the Box event.";
			logger.logp(Level.SEVERE, CLASS_NAME, logMsg, ex.getLocalizedMessage());
		}
	
		return isSupported;
	}
	
	/**
	 *  Processes the Box document UPLOAD event to copy the Box document to the caseId. 
	 *  
	 *  @param boxEvent
	 *  	   The box event that gets processed if the event handler supports it (determined in eventSupported())
	 *  @param caseId
	 *  	   The case id that is associated with this Box account.
	 */
	@Override
	public void processBoxEvent(BoxEvent boxEvent, String caseId) {
		final String functionName = "processBoxEvent";
		logger.logp(Level.FINE, CLASS_NAME, functionName, "Starting event handler to process Box document upload event.");
		String solName = getSolutionName(caseId);
		
		//If the event occurs for the specified solution, start a task to copy the Box document to the a new Case.
		if ((solName != null) && (solName.equalsIgnoreCase(this.solutionName))) {
			String boxDocId = this.getBoxDocumentId(boxEvent);
			String boxDocName = this.getBoxDocumentName(boxEvent);
			
			//get a task service
			TaskService taskService = getTaskService();
			if (taskService == null) {
				// Error retrieving a task service
				logger.logp(Level.SEVERE, CLASS_NAME, functionName, "Error getting task service");
				return;
			} else {
				// Successfully retrieved a task service, now create a task schedule request and then schedule it
				JSONObject taskRequest = constructTaskScheduleRequest(boxDocId, caseId, boxDocName);
				JSONObject result = taskService.scheduleTask(null, taskRequest);
				logger.logp(Level.FINE, CLASS_NAME, functionName, "Result from scheduling a task" + result.toString());
			}
		}
		else
			logger.logp(Level.FINE, CLASS_NAME, functionName, "Skipping process... Box event occured on different solution. Event solution: " + solName);

		logger.logp(Level.FINE, CLASS_NAME, functionName, "Completed event handler for Box document upload event.");
	}
	
	// Extra functionality not required by the Base Event Handler. 
	/**
	 * Constructs the task schedule request to copy the Box document to the case.
	 * 
	 * @param boxDocId The Box document id to copy.
	 * @param caseId The case id where the Task will copy the Box document to.
	 * @param boxDocName The Box document display name for logging purposes.
	 * @return taskRequest The task request JSON to create and start.
	 */
	protected JSONObject constructTaskScheduleRequest(String boxDocId, String caseId, String boxDocName) {
		JSONObject taskRequest = new JSONObject();
		
		//prepare the task with Box document ID, case ID, and Box document name needed for copying
		taskRequest.put(Constants.PARAM_NAME, getLocalizedMsg("A0819I.ICM_COPY_BOX_DOC_TASK_INSTANT_NAME", boxDocName));
		if (bFileDocToCase)
			taskRequest.put(Constants.PARAM_DESCRIPTION, getLocalizedMsg("A0821I.ICM_COPY_BOX_DOC_TASK_DESCRIPTION_CASE", p8DocType));
		else
			taskRequest.put(Constants.PARAM_DESCRIPTION, getLocalizedMsg("A0820I.ICM_COPY_BOX_DOC_TASK_DESCRIPTION_P8", p8DocType));
		taskRequest.put(Constants.PARAM_HANDLER_CLASS_NAME, EventHandlerConstants.BOX_COPY_TASK_CLASS_ID);
		taskRequest.put(Constants.PARAM_PARENT, "Navigator");
		taskRequest.put(Constants.PARAM_LOG_LEVEL, Constants.LOG_LEVEL_FINE);
		
		// start task now.
		taskRequest.put(Constants.PARAM_START_DATE_TIME, new Long(System.currentTimeMillis()));
		
		// send task specific parameters.
		taskRequest.put(EventHandlerConstants.JSON_CASE_ID, caseId);
		taskRequest.put(EventHandlerConstants.JSON_BOX_DOC_ID, boxDocId);
		taskRequest.put(EventHandlerConstants.JSON_INITIATION_DOC_TYPE, p8DocType);
		taskRequest.put(EventHandlerConstants.JSON_REPOSITORY_ID, targetRepositoryId);
		taskRequest.put(EventHandlerConstants.JSON_FILE_DOC_TO_CASE, bFileDocToCase);
		
		return taskRequest;
	}
	
	/**
	 * Retrieves the solution name from given case id. 
	 * Note: This is also to validate the case id to make sure it belongs to a solution.
	 * 
	 * @param caseId The case to retrieve the solution from.
	 * @return solFolder The name of the solution.
	 */
	protected String getSolutionName(String caseId) {
		String solutionFolder = null;
		try {
	        ObjectStoreReference targetOSRef = P8ConnectionUtil.getTargetOSRef(targetRepositoryId);

	    	PropertyFilter propertyFilter = new PropertyFilter();
	        propertyFilter.addIncludeProperty(1, null, null, PropertyNames.ID, null);
	        propertyFilter.addIncludeProperty(1, null, null, CEConstants.PARENT_SOLUTION_PROPERTY_NAME, null);
	        Folder caseFolder = Factory.Folder.fetchInstance(targetOSRef.fetchCEObject(), new Id(caseId), propertyFilter);
	        
	        Folder solFolder = (Folder) caseFolder.getProperties().getObjectValue(CEConstants.PARENT_SOLUTION_PROPERTY_NAME);
	        solFolder.refresh(new String[] {PropertyNames.FOLDER_NAME});
	        solutionFolder = solFolder.get_FolderName();
		} catch (Exception ex) {
			String logMsg =  "Validation error: Cannot get solution name from case id:" + caseId;
			logger.logp(Level.SEVERE, CLASS_NAME,  logMsg, ex.getLocalizedMessage());
		} finally {
			UserContext userCtx = UserContext.get();
			if (userCtx != null)
				userCtx.popSubject();
		}
		
		return solutionFolder;
	}
	
	protected String getBoxDocumentId(BoxEvent boxEvent) {
		String boxDocId = boxEvent.getSourceJSON().getString(BoxConstants.JSON_ID, "");
		return boxDocId;
	}
	
	protected String getBoxDocumentName(BoxEvent boxEvent) {
		String boxDocName = boxEvent.getSourceJSON().getString(BoxConstants.JSON_NAME, "");
		return boxDocName;
	}
	
	protected String getLocalizedMsg(String labelID, String param) {
        Message msgBundle;
        if (param != null)
        	msgBundle = new Message(labelID, param);
        else
        	msgBundle = new Message(labelID);
        return msgBundle.getFormattedText();
	}
	
	protected TaskService getTaskService() {
		ServiceManager serviceManager;
		Properties props = null; 
		
		try {
			props = P8ConnectionUtil.getTaskMgrLogin(targetRepositoryId);
		}
		catch (Exception ex) {
			logger.logp(Level.SEVERE, CLASS_NAME, "getTaskService", ex.getMessage());
			return null;
		}
	
		serviceManager = DefaultServiceManagerFactory.getInstance().getServiceManager(props);
		TaskService taskService = serviceManager.getTaskService();
		return taskService;
	}
}
