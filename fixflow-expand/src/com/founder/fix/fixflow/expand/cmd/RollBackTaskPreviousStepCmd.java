/**
 * Copyright 1996-2013 Founder International Co.,Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * @author kenshin
 */
package com.founder.fix.fixflow.expand.cmd;

import java.util.List;


import com.founder.fix.fixflow.core.exception.FixFlowException;
import com.founder.fix.fixflow.core.factory.ProcessObjectFactory;
import com.founder.fix.fixflow.core.impl.bpmn.behavior.ProcessDefinitionBehavior;
import com.founder.fix.fixflow.core.impl.bpmn.behavior.TaskCommandInst;
import com.founder.fix.fixflow.core.impl.bpmn.behavior.UserTaskBehavior;
import com.founder.fix.fixflow.core.impl.cmd.AbstractExpandTaskCmd;
import com.founder.fix.fixflow.core.impl.cmd.GetPreviousStepTaskByTaskIdCmd;
import com.founder.fix.fixflow.core.impl.expression.ExpressionMgmt;
import com.founder.fix.fixflow.core.impl.identity.Authentication;
import com.founder.fix.fixflow.core.impl.interceptor.CommandContext;
import com.founder.fix.fixflow.core.impl.persistence.ProcessDefinitionManager;
import com.founder.fix.fixflow.core.impl.persistence.ProcessInstanceManager;
import com.founder.fix.fixflow.core.impl.persistence.TaskManager;
import com.founder.fix.fixflow.core.impl.runtime.ProcessInstanceEntity;
import com.founder.fix.fixflow.core.impl.runtime.TokenEntity;
import com.founder.fix.fixflow.core.impl.task.TaskInstanceEntity;
import com.founder.fix.fixflow.core.impl.util.StringUtil;
import com.founder.fix.fixflow.core.runtime.ExecutionContext;
import com.founder.fix.fixflow.core.task.TaskInstance;
import com.founder.fix.fixflow.expand.command.RollBackTaskPreviousStepCommand;

public class RollBackTaskPreviousStepCmd extends AbstractExpandTaskCmd<RollBackTaskPreviousStepCommand,Void> {

	

	public RollBackTaskPreviousStepCmd(RollBackTaskPreviousStepCommand expandTaskCommand) {
		super(expandTaskCommand);
		//this.rollBackTaskId = expandTaskCommand.getRollBackTaskId();
		// TODO Auto-generated constructor stub
	}

	public Void execute(CommandContext commandContext) {
		
		if (taskId == null||taskId.equals("")) {
			throw new FixFlowException("任务编号为空！");
		}
		
		// TODO Auto-generated method stub
		List<TaskInstance> taskInstanceQueryTos = (new GetPreviousStepTaskByTaskIdCmd(this.taskId)).execute(commandContext);

		if (taskInstanceQueryTos.size() == 0) {
			throw new FixFlowException("没有可以退回的任务");
		}

		TaskManager taskManager = commandContext.getTaskManager();

		TaskInstance taskInstanceQuery = taskManager.findTaskById(taskId);

		String tokenId = taskInstanceQuery.getTokenId();
		String nodeId = taskInstanceQuery.getNodeId();
		String processDefinitionId = taskInstanceQuery.getProcessDefinitionId();
		ProcessInstanceManager processInstanceManager = commandContext.getProcessInstanceManager();

		String processInstanceId = taskInstanceQuery.getProcessInstanceId();

		ProcessDefinitionManager processDefinitionManager = commandContext.getProcessDefinitionManager();

		ProcessDefinitionBehavior processDefinition = processDefinitionManager.findLatestProcessDefinitionById(processDefinitionId);

		UserTaskBehavior userTask = (UserTaskBehavior) processDefinition.getDefinitions().getElement(nodeId);

		TaskCommandInst taskCommand=null;
		
		String taskCommandType = expandTaskCommand.getCommandType();
		
		if (StringUtil.isNotEmpty(this.admin) && StringUtil.isEmpty(this.userCommandId) && StringUtil.isNotEmpty(taskCommandType)) {

			String taskCommandName = commandContext.getProcessEngineConfigurationImpl().getTaskCommandDefMap().get(taskCommandType).getName();

			taskCommand = new TaskCommandInst(taskCommandType, taskCommandName, null, taskCommandType, true);

		} else {
			taskCommand = userTask.getTaskCommandsMap().get(this.userCommandId);
		}
		
		ProcessInstanceEntity processInstanceImpl = processInstanceManager.findProcessInstanceById(processInstanceId, processDefinition);

		processInstanceImpl.getContextInstance().setVariableMap(variables);

		TokenEntity token = processInstanceImpl.getTokenMap().get(tokenId);

		processInstanceImpl.getContextInstance().setTransientVariableMap(transientVariables);
		processInstanceImpl.getContextInstance().setVariableMap(variables);
		

		ExecutionContext executionContext = ProcessObjectFactory.FACTORYINSTANCE.createExecutionContext(token);

		
		
		if (taskCommand != null && taskCommand.getExpression() != null) {
			try {
				
				ExpressionMgmt.execute(taskCommand.getExpression(), executionContext);
			} catch (Exception e) {
				throw new FixFlowException("用户命令表达式执行异常!", e);
			}
		}
		
		
		
		if (taskInstanceQueryTos.size() > 1||(taskInstanceQueryTos.size()==1&&taskInstanceQueryTos.get(0).getTaskGroup()!=null)) {

			if (taskCommand != null) {

				String taskId = this.taskId;
				String taskComment = this.taskComment;
				String rollBackNodeId = taskInstanceQueryTos.get(0).getNodeId();

				List<TaskInstanceEntity> taskInstances = processInstanceImpl.getTaskMgmtInstance().getTaskInstanceEntitys();
				TaskInstanceEntity taskInstanceImpl = null;
				for (TaskInstanceEntity taskInstance : taskInstances) {
					if (taskInstance.getId().equals(taskId)) {
						taskInstanceImpl = taskInstance;
					}
				}

				if (taskInstanceImpl == null) {
					throw new FixFlowException("需要退回的任务: " + taskId + " 不存在!");
				}
				if(this.agent!=null&&!this.agent.equals("")){
					taskInstanceImpl.setAgent(Authentication.getAuthenticatedUserId());
					taskInstanceImpl.setAssigneeWithoutCascade(this.agent);
				}else{
					taskInstanceImpl.setAssigneeWithoutCascade(Authentication.getAuthenticatedUserId());
					taskInstanceImpl.setAgent(null);
				}
				try {

				
					
					UserTaskBehavior backNodeUserTask = (UserTaskBehavior) executionContext.getProcessDefinition().getDefinitions().getElement(rollBackNodeId);
					taskInstanceImpl.toFlowNodeEnd(taskCommand, taskComment, this.agent, this.admin, backNodeUserTask, null);

					
					

				} catch (Exception e) {

					throw new FixFlowException("任务: " + taskId + " 退回失败!", e);
				}

			}else{
				throw new FixFlowException("未点击任务处理按钮,任务处理失败。");
			}

			try {
				processInstanceManager.saveProcessInstance(processInstanceImpl);
			} catch (Exception e) {
				throw new FixFlowException("流程实例持久化失败!", e);
			}
			return null;

		} else {

			if (taskCommand != null) {

				String taskId = this.taskId;
				String taskComment = this.taskComment;
				String rollBackNodeId = taskInstanceQueryTos.get(0).getNodeId();

				List<TaskInstanceEntity> taskInstances = processInstanceImpl.getTaskMgmtInstance().getTaskInstanceEntitys();
				TaskInstanceEntity taskInstanceImpl = null;
				for (TaskInstanceEntity taskInstance : taskInstances) {
					if (taskInstance.getId().equals(taskId)) {
						taskInstanceImpl =taskInstance;
					}
				}

				if (taskInstanceImpl == null) {
					throw new FixFlowException("需要退回的任务: " + taskId + " 不存在!");
				}
				try {

					if(this.agent!=null&&!this.agent.equals("")){
						taskInstanceImpl.setAgent(Authentication.getAuthenticatedUserId());
						taskInstanceImpl.setAssigneeWithoutCascade(this.agent);
					}else{
						taskInstanceImpl.setAssigneeWithoutCascade(Authentication.getAuthenticatedUserId());
						taskInstanceImpl.setAgent(null);
					}
					UserTaskBehavior backNodeUserTask = (UserTaskBehavior) executionContext.getProcessDefinition().getDefinitions().getElement(rollBackNodeId);
					taskInstanceImpl.toFlowNodeEnd(taskCommand, taskComment, this.agent, this.admin, backNodeUserTask, taskInstanceQueryTos.get(0).getAssignee());


				} catch (Exception e) {

					throw new FixFlowException("任务: " + taskId + " 退回失败!", e);
				}

			}

			try {
				processInstanceManager.saveProcessInstance(processInstanceImpl);
			} catch (Exception e) {
				throw new FixFlowException("流程实例持久化失败!", e);
			}
			return null;

		}

	}

	
}

