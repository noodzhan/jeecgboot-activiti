package org.jeecg.modules.activiti.web;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.editor.constants.ModelDataJsonConstants;
import org.activiti.engine.HistoryService;
import org.activiti.engine.ProcessEngineConfiguration;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.repository.Model;
import org.activiti.engine.repository.ModelQuery;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.activiti.image.ProcessDiagramGenerator;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.modules.activiti.entity.ActZprocess;
import org.jeecg.modules.activiti.service.Impl.ActZprocessServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/activiti/models")
@Slf4j
public class ActivitiModelController {

  @Autowired private RepositoryService repositoryService;
  @Autowired private HistoryService historyService;
  @Autowired private RuntimeService runtimeService;
  @Autowired private ProcessEngineConfiguration processEngineConfiguration;
  @Autowired private TaskService taskService;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ActZprocessServiceImpl actZprocessService;

  @RequestMapping("/modelListData")
  @ResponseBody
  public Result modelListData(HttpServletRequest request) {
    log.info("-------------模型列表-------------");
    ModelQuery modelQuery = repositoryService.createModelQuery();
    String keyWord = request.getParameter("keyWord"); // 搜索关键字
    if (StrUtil.isNotBlank(keyWord)) {
      modelQuery.modelNameLike("%" + keyWord + "%");
    }
    List<Model> models = modelQuery.orderByCreateTime().desc().list();

    return Result.ok(models);
  }

  @RequestMapping("/delete/{id}")
  public @ResponseBody Result deleteModel(@PathVariable("id") String id) {
    repositoryService.deleteModel(id);
    return Result.ok("删除成功。");
  }

  @RequestMapping("/deployment/{id}")
  public @ResponseBody Result deploy(@PathVariable("id") String id) {

    // 获取模型
    Model modelData = repositoryService.getModel(id);
    byte[] bytes = repositoryService.getModelEditorSource(modelData.getId());

    if (bytes == null) {
      return Result.error("模型数据为空，请先成功设计流程并保存");
    }

    try {

      // 部署发布模型流程
      String processName = modelData.getName() + ".bpmn20.xml";
      Deployment deployment =
          repositoryService
              .createDeployment()
              .name(modelData.getName())
              .addString(processName, new String(bytes, "UTF-8"))
              .deploy();
      String metaInfo = modelData.getMetaInfo();
      JSONObject metaInfoMap = JSON.parseObject(metaInfo);
      // 设置流程分类 保存扩展流程至数据库
      List<ProcessDefinition> list =
          repositoryService.createProcessDefinitionQuery().deploymentId(deployment.getId()).list();
      for (ProcessDefinition pd : list) {
        ActZprocess actZprocess = new ActZprocess();
        actZprocess.setId(pd.getId());
        actZprocess.setName(modelData.getName());
        actZprocess.setProcessKey(modelData.getKey());
        actZprocess.setDeploymentId(deployment.getId());
        actZprocess.setDescription(metaInfoMap.getString(ModelDataJsonConstants.MODEL_DESCRIPTION));
        actZprocess.setVersion(pd.getVersion());
        actZprocess.setDiagramName(pd.getDiagramResourceName());
        actZprocessService.setAllOldByProcessKey(modelData.getKey());
        actZprocess.setLatest(true);
        actZprocessService.save(actZprocess);
      }
    } catch (Exception e) {
      String err = e.toString();
      log.error(e.getMessage(), e);
      if (err.indexOf("NCName") > -1) {
        return Result.error("部署失败：流程设计中的流程名称不能为空，不能为数字以及特殊字符开头！");
      }
      if (err.indexOf("PRIMARY") > -1) {
        return Result.error("部署失败：该模型已发布，key唯一！");
      }
      return Result.error("部署失败！");
    }

    return Result.ok("部署成功");
  }

  /*获取高亮实时流程图*/
  @RequestMapping(value = "/getHighlightImg/{id}", method = RequestMethod.GET)
  public void getHighlightImg(@PathVariable String id, HttpServletResponse response) {
    InputStream inputStream = null;
    ProcessInstance pi = null;
    String picName = "";
    // 查询历史
    HistoricProcessInstance hpi =
        historyService.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult();
    if (hpi.getEndTime() != null) {
      // 已经结束流程获取原图
      ProcessDefinition pd =
          repositoryService
              .createProcessDefinitionQuery()
              .processDefinitionId(hpi.getProcessDefinitionId())
              .singleResult();
      picName = pd.getDiagramResourceName();
      inputStream =
          repositoryService.getResourceAsStream(pd.getDeploymentId(), pd.getDiagramResourceName());
    } else {
      pi = runtimeService.createProcessInstanceQuery().processInstanceId(id).singleResult();
      BpmnModel bpmnModel = repositoryService.getBpmnModel(pi.getProcessDefinitionId());

      List<String> highLightedActivities = new ArrayList<String>();
      // 高亮任务节点
      List<Task> tasks = taskService.createTaskQuery().processInstanceId(id).list();
      for (Task task : tasks) {
        highLightedActivities.add(task.getTaskDefinitionKey());
      }

      List<String> highLightedFlows = new ArrayList<String>();
      ProcessDiagramGenerator diagramGenerator =
          processEngineConfiguration.getProcessDiagramGenerator();
      // "宋体"
      inputStream =
          diagramGenerator.generateDiagram(
              bpmnModel,
              "png",
              highLightedActivities,
              highLightedFlows,
              "宋体",
              "宋体",
              "宋体",
              null,
              1.0);
      picName = pi.getName() + ".png";
    }
    try {
      response.setContentType("application/octet-stream;charset=UTF-8");
      response.setHeader(
          "Content-Disposition", "attachment; filename=" + URLEncoder.encode(picName, "UTF-8"));
      byte[] b = new byte[1024];
      int len = -1;
      while ((len = inputStream.read(b, 0, 1024)) != -1) {
        response.getOutputStream().write(b, 0, len);
      }
      response.flushBuffer();
    } catch (IOException e) {
      log.error(e.toString());
      throw new JeecgBootException("读取流程图片失败");
    }
  }
  /** 导出部署流程资源 */
  // 获取流程图片的接口
  @RequestMapping(value = "/export", method = RequestMethod.GET)
  public void exportResource(@RequestParam String id, HttpServletResponse response) {

    ProcessDefinition pd =
        repositoryService.createProcessDefinitionQuery().processDefinitionId(id).singleResult();

    String resourceName = pd.getDiagramResourceName();
    InputStream inputStream =
        repositoryService.getResourceAsStream(pd.getDeploymentId(), resourceName);

    try {
      response.setContentType("application/octet-stream;charset=UTF-8");
      response.setHeader(
          "Content-Disposition",
          "attachment; filename=" + URLEncoder.encode(resourceName, "UTF-8"));
      byte[] b = new byte[1024];
      int len = -1;
      while ((len = inputStream.read(b, 0, 1024)) != -1) {
        response.getOutputStream().write(b, 0, len);
      }
      response.flushBuffer();
    } catch (IOException e) {
      log.error(e.toString());
    }
  }
}