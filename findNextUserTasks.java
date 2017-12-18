package com.kf.oa.workflow.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Resource;

import org.activiti.engine.ProcessEngines;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.impl.RepositoryServiceImpl;
import org.activiti.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.activiti.engine.impl.javax.el.ExpressionFactory;
import org.activiti.engine.impl.javax.el.ValueExpression;
import org.activiti.engine.impl.juel.ExpressionFactoryImpl;
import org.activiti.engine.impl.juel.SimpleContext;
import org.activiti.engine.impl.persistence.entity.ExecutionEntity;
import org.activiti.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.activiti.engine.impl.pvm.PvmActivity;
import org.activiti.engine.impl.pvm.PvmTransition;
import org.activiti.engine.impl.pvm.process.ActivityImpl;
import org.activiti.engine.impl.task.TaskDefinition;
import org.activiti.engine.runtime.Execution;
import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Service;

import com.kf.oa.workflow.entity.FlowInfo;
import com.kf.oa.workflow.service.ICreateFlow;

/**
 * 获取后续流程节点参数
 * @author qyp
 */
@Service("createFlow")
public class CreateFlow implements ICreateFlow {

	private TaskService taskService = ProcessEngines.getDefaultProcessEngine().getTaskService() ;
	private RuntimeService runtimeService = ProcessEngines.getDefaultProcessEngine().getRuntimeService();
//	private RepositoryServiceImpl repositoryService = ProcessEngines.getDefaultProcessEngine().getRepositoryService();
	@Resource
	private RepositoryServiceImpl repositoryService;
	
	//单子启动时设置的参数
	Map<String, Object> runTimeVariables = new HashMap<>();

	/**
	 * 取出获取到的节点的有用参数：group、taskName
	 */
	@Override
	public List<FlowInfo> getFlowList(String taskId) throws Exception {
		String processInstanceId = taskService.createTaskQuery().taskId(taskId).singleResult().getProcessInstanceId();
		List<TaskDefinition> taskList = getNextTaskInfo(processInstanceId);
		List<FlowInfo> flowInfoList = new LinkedList<>();
		for (TaskDefinition taskDefinition : taskList) {
			FlowInfo flowInfo = new FlowInfo(taskDefinition.getNameExpression().toString(), taskDefinition.getCandidateGroupIdExpressions().toString(), taskDefinition.getKey());
			flowInfoList.add(flowInfo);
		}
		return flowInfoList;
	}
	
    /** 
     * 依据启动参数获取当前节点后的所有节点
     * @param String taskId     任务Id信息  
     * @return  下一个用户任务用户组信息  
     * @throws Exception 
     */ 
    private List<TaskDefinition> getNextTaskInfo(String processInstanceId) throws Exception {  
		
		runTimeVariables = getAllRunTimeVariables(processInstanceId);
		
        ProcessDefinitionEntity processDefinitionEntity = null;  

        String id = null;  

        TaskDefinition task = null;  
        List<TaskDefinition> taskList = new ArrayList<>();

        //获取流程发布Id信息   
        String definitionId = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult().getProcessDefinitionId();  
        
        processDefinitionEntity = (ProcessDefinitionEntity) ((RepositoryServiceImpl) repositoryService).getDeployedProcessDefinition(definitionId);
        ExecutionEntity execution = (ExecutionEntity) runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();  

        //当前流程节点Id信息   
        String activitiId = execution.getActivityId();

        //获取流程所有节点信息
        List<ActivityImpl> activitiList = processDefinitionEntity.getActivities();

        //遍历所有节点信息
        for(ActivityImpl activityImpl : activitiList){
            id = activityImpl.getId();
            if (activitiId.equals(id)) {
                //获取下一个节点信息
            	TaskDefinition nowTask = ((UserTaskActivityBehavior) activityImpl.getActivityBehavior()).getTaskDefinition();
            	if(nowTask != null)
                	taskList.add(nowTask);
                task = nextTaskDefinition(activityImpl, activityImpl.getId(), null, processInstanceId);
                if(task != null)
                	taskList.add(task);
                break;
            }
        }
        
        while(task != null && !(task.getKey().contains("end"))){
        	for(ActivityImpl activityImpl : activitiList){      
	            id = activityImpl.getId();     
	            if ((task.getKey()).equals(id)) {
	                //获取下一个节点信息   
	                task = nextTaskDefinition(activityImpl, activityImpl.getId(), null, processInstanceId); 
	                if(task != null)
	                	taskList.add(task);
	                break;
	            }
	        }
        	if(task==null) break;
        }
        return taskList;  
    }  

    /**  
     * 下一个任务节点信息,  
     *  
     * 如果下一个节点为用户任务则直接返回,  
     *  
     * 如果下一个节点为排他网关, 获取排他网关Id信息, 根据排他网关Id信息和execution获取流程实例排他网关Id为key的变量值,  
     * 根据变量值分别执行排他网关后线路中的el表达式, 并找到el表达式通过的线路后的用户任务
     * @param ActivityImpl activityImpl     流程节点信息  
     * @param String activityId             当前流程节点Id信息  
     * @param String elString               排他网关顺序流线段判断条件
     * @param String processInstanceId      流程实例Id信息  
     * @return  
     */    
    private TaskDefinition nextTaskDefinition(ActivityImpl activityImpl, String activityId, String elString, String processInstanceId){   

        PvmActivity ac = null;

        Object s = null;

        // 如果遍历节点为用户任务并且节点不是当前节点信息
        if ("userTask".equals(activityImpl.getProperty("type")) && !activityId.equals(activityImpl.getId())) {
            // 获取该节点下一个节点信息
            TaskDefinition taskDefinition = ((UserTaskActivityBehavior) activityImpl.getActivityBehavior())
                    .getTaskDefinition();
            return taskDefinition;
        } else if("exclusiveGateway".equals(activityImpl.getProperty("type"))){// 当前节点为exclusiveGateway
            List<PvmTransition> outTransitions = activityImpl.getOutgoingTransitions();
            // 如果排他网关只有一条线路信息
            if (outTransitions.size() == 1) {
                return nextTaskDefinition((ActivityImpl) outTransitions.get(0).getDestination(), activityId,
                        elString, processInstanceId);
            } else if (outTransitions.size() > 1) { // 如果排他网关有多条线路信息
                for (PvmTransition tr1 : outTransitions) {
                    s = tr1.getProperty("conditionText"); // 获取排他网关线路判断条件信息
                    // 判断el表达式是否成立
                    if (isCondation(StringUtils.trim(s.toString()))) {
                        return nextTaskDefinition((ActivityImpl) tr1.getDestination(), activityId, elString,
                                processInstanceId);
                    }
                }
            }
        }else {
            // 获取节点所有流向线路信息
            List<PvmTransition> outTransitions = activityImpl.getOutgoingTransitions();
            List<PvmTransition> outTransitionsTemp = null;
            for (PvmTransition tr : outTransitions) {
                ac = tr.getDestination(); // 获取线路的终点节点
                // 如果流向线路为排他网关
                if ("exclusiveGateway".equals(ac.getProperty("type"))) {
                    outTransitionsTemp = ac.getOutgoingTransitions();
                    // 如果排他网关只有一条线路信息
                    if (outTransitionsTemp.size() == 1) {
                        return nextTaskDefinition((ActivityImpl) outTransitionsTemp.get(0).getDestination(), activityId,
                                elString, processInstanceId);
                    } else if (outTransitionsTemp.size() > 1) { // 如果排他网关有多条线路信息
                        for (PvmTransition tr1 : outTransitionsTemp) {
                            s = tr1.getProperty("conditionText"); // 获取排他网关线路判断条件信息
                            // 判断el表达式是否成立
                            if (isCondation(StringUtils.trim(s.toString()))) {
                                return nextTaskDefinition((ActivityImpl) tr1.getDestination(), activityId, elString,processInstanceId);
                            }
                        }
                    }
                } else if ("userTask".equals(ac.getProperty("type"))) {
                    return ((UserTaskActivityBehavior) ((ActivityImpl) ac).getActivityBehavior()).getTaskDefinition();
                } else {
                }
            }
            return null;
        }
        return null;
    }  

    /** 
     * 查询流程启动时设置排他网关判断条件信息  
     * @param String gatewayId          排他网关Id信息, 流程启动时设置网关路线判断条件key为网关Id信息  
     * @param String processInstanceId  流程实例Id信息  
     * @return 
     */  
    public String getGatewayCondition(String gatewayId, String processInstanceId) {  
        Execution execution = runtimeService.createExecutionQuery().processInstanceId(processInstanceId).singleResult();
        Object object= runtimeService.getVariable(execution.getId(), gatewayId);
        return object==null? "adopt":object.toString();  
    }
    
    /**
     * 获得改流程运行时参数
     * @param processInstanceId
     * @return
     * @Date 2017年12月15日
     * @author qyp
     */
    private Map<String, Object> getAllRunTimeVariables(String processInstanceId) {
    	Execution execution = runtimeService.createExecutionQuery().processInstanceId(processInstanceId).singleResult();
    	Map<String, Object> map = runtimeService.getVariables(execution.getId());
    	return map;
	}

    /** 
     * 根据key和value判断el表达式是否通过信息  
     * @param String key    el表达式key信息  
     * @param String el     el表达式信息  
     * @param String value  el表达式传入值信息  
     * @return 
     */  
    public boolean isCondition(String key, String el, String value) {  
        ExpressionFactory factory = new ExpressionFactoryImpl();    
        SimpleContext context = new SimpleContext(); 
        context.setVariable(key, factory.createValueExpression(value, String.class));
        ValueExpression e = factory.createValueExpression(context, el, boolean.class);    
        return (Boolean) e.getValue(context);  
    }
    
    /**
     * 参数设置.
     * (1)设置默认通过驳回(2)检验flow流向
     * @param el
     * @return
     * @Date 2017年12月15日
     * @author qyp
     */
    private boolean isCondation(String el){
    	if(el.equals("${approval==\"adopt\"}") || el.equals("${approval==\"adjustment\"}")){
    		//通过、驳回、调整、作废
    		return true;
    	}else if(el.equals("${approval==\"reject\"}") ||  el.equals("${approval==\"end\"}")) {
    		return false;
		}
    	String checkKey = el.substring(el.indexOf("{")+1, indexOfFirstChar("=",el)).trim();
    	Object value = runTimeVariables.get(checkKey);
    	if(value == null){
    		value = runTimeVariables.get(checkKey.replace("!", ""));
//    		if(!(boolean) value){
//    			value = true;
//    		}
    	}
    	ExpressionFactory factory = new ExpressionFactoryImpl();
    	SimpleContext context = new SimpleContext();
    	if(checkKey.contains("!")){
    		checkKey = checkKey.replace("!", "");
    	}
    	context.setVariable(checkKey, factory.createValueExpression(value, Object.class));
    	ValueExpression e = factory.createValueExpression(context, el, boolean.class);
    	return (Boolean) e.getValue(context);
    }
    
    /**
     * 获取checkStr在str中第一次出现的位置
     * @param checkStr
     * @param str
     * @return
     * @Date 2017年12月15日
     * @author qyp
     */
    private int indexOfFirstChar(String checkStr,String str) {
    	Matcher matcher = Pattern.compile(checkStr).matcher(str);  
    	if(matcher.find()){  
    		return matcher.start(); 
    	}else{  
    		return str.length()-1;
    	}  
    }
}
