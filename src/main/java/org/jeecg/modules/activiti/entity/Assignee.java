package org.jeecg.modules.activiti.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Assignee {

  private String username;

  private Boolean isExecutor;
}
