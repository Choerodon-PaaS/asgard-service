package io.choerodon.asgard.api.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.choerodon.asgard.api.dto.SagaTaskInstanceDTO;
import io.choerodon.asgard.api.dto.SagaTaskInstanceInfoDTO;
import io.choerodon.asgard.api.dto.SagaTaskInstanceStatusDTO;
import io.choerodon.asgard.api.service.NoticeService;
import io.choerodon.asgard.api.service.SagaTaskInstanceService;
import io.choerodon.asgard.domain.JsonData;
import io.choerodon.asgard.domain.SagaInstance;
import io.choerodon.asgard.domain.SagaTaskInstance;
import io.choerodon.asgard.infra.mapper.JsonDataMapper;
import io.choerodon.asgard.infra.mapper.SagaInstanceMapper;
import io.choerodon.asgard.infra.mapper.SagaTaskInstanceMapper;
import io.choerodon.asgard.infra.utils.ConvertUtils;
import io.choerodon.asgard.saga.SagaDefinition;
import io.choerodon.asgard.saga.dto.PollBatchDTO;
import io.choerodon.core.domain.Page;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.exception.FeignException;
import io.choerodon.mybatis.pagehelper.PageHelper;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;

import static io.choerodon.asgard.api.service.impl.SagaInstanceServiceImpl.DB_ERROR;
import static java.util.stream.Collectors.groupingBy;
import static org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ;

@Service
public class SagaTaskInstanceServiceImpl implements SagaTaskInstanceService {

    private static final String ERROR_CODE_TASK_INSTANCE_NOT_EXIST = "error.sagaTaskInstance.notExist";

    private static final Logger LOGGER = LoggerFactory.getLogger(SagaTaskInstanceService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private SagaTaskInstanceMapper taskInstanceMapper;
    private SagaInstanceMapper instanceMapper;
    private JsonDataMapper jsonDataMapper;
    private DataSourceTransactionManager transactionManager;
    private NoticeService noticeService;

    public SagaTaskInstanceServiceImpl(SagaTaskInstanceMapper taskInstanceMapper,
                                       SagaInstanceMapper instanceMapper,
                                       JsonDataMapper jsonDataMapper,
                                       DataSourceTransactionManager transactionManager,
                                       NoticeService noticeService) {
        this.taskInstanceMapper = taskInstanceMapper;
        this.instanceMapper = instanceMapper;
        this.jsonDataMapper = jsonDataMapper;
        this.transactionManager = transactionManager;
        this.noticeService = noticeService;
        objectMapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);
    }

    @Override
    public Set<SagaTaskInstanceDTO> pollBatch(final PollBatchDTO pollBatchDTO) {
        final Set<SagaTaskInstanceDTO> returnList = new LinkedHashSet<>();
        pollBatchDTO.getCodes().forEach(code -> {
            //并发策略为NONE的消息拉取。
            taskInstanceMapper.pollBatchNoneLimit(
                    code.getSagaCode(), code.getTaskCode(), pollBatchDTO.getInstance()).forEach(t -> {
                if (returnList.size() >= pollBatchDTO.getMaxPollSize()) {
                    return;
                }
                addToReturnList(returnList, pollBatchDTO.getInstance(), t);
            });

            //并发策略为TYPE_AND_ID的消息拉取。
            taskInstanceMapper.pollBatchTypeAndIdLimit(
                    code.getSagaCode(), code.getTaskCode()).stream()
                    .collect(groupingBy(t -> t.getRefType() + ":" + t.getRefId())).values()
                    .forEach(i -> {
                        if (returnList.size() >= pollBatchDTO.getMaxPollSize()) {
                            return;
                        }
                        addLimit(returnList, i, pollBatchDTO.getInstance());
                    });
            //并发策略为TYPE的消息拉取。
            taskInstanceMapper.pollBatchTypeLimit(
                    code.getSagaCode(), code.getTaskCode()).stream()
                    .collect(groupingBy(SagaTaskInstanceDTO::getRefType)).values()
                    .forEach(i -> {
                        if (returnList.size() >= pollBatchDTO.getMaxPollSize()) {
                            return;
                        }
                        addLimit(returnList, i, pollBatchDTO.getInstance());
                    });
        });
        return returnList;
    }

    private void addLimit(final Set<SagaTaskInstanceDTO> returnList,
                          final List<SagaTaskInstanceDTO> list,
                          final String instance) {
        int currentLimitNum = list.get(0).getConcurrentLimitNum();
        list.stream().sorted(Comparator.comparing(SagaTaskInstanceDTO::getCreationDate)).limit(currentLimitNum).forEach(j ->
                addToReturnList(returnList, instance, j)
        );
    }

    private void addToReturnList(final Set<SagaTaskInstanceDTO> returnList,
                                 final String instance,
                                 final SagaTaskInstanceDTO j) {
        Date time = null;
        if (j.getActualStartTime() == null) {
            time = new Date();
        }
        if (j.getInstanceLock() == null) {
            if (taskInstanceMapper.lockByInstanceAndUpdateStartTime(j.getId(), instance, j.getObjectVersionNumber(), time) == 1) {
                returnList.add(j);
            }
        } else if (j.getInstanceLock().equals(instance)) {
            returnList.add(j);
        }
    }

    @Override
    public void updateStatus(final SagaTaskInstanceStatusDTO statusDTO) {
        SagaTaskInstance taskInstance = taskInstanceMapper.selectByPrimaryKey(statusDTO.getId());
        if (taskInstance == null) {
            throw new FeignException(ERROR_CODE_TASK_INSTANCE_NOT_EXIST);
        }
        SagaInstance instance = instanceMapper.selectByPrimaryKey(taskInstance.getSagaInstanceId());
        if (instance == null) {
            throw new FeignException("error.sagaInstance.notExist");
        }
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setIsolationLevel(ISOLATION_REPEATABLE_READ);
        def.setPropagationBehavior(DefaultTransactionDefinition.PROPAGATION_REQUIRED);
        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            if (SagaDefinition.TaskInstanceStatus.COMPLETED.name().equalsIgnoreCase(statusDTO.getStatus())) {
                updateStatusCompleted(taskInstance, statusDTO.getOutput(), instance);
            } else if (SagaDefinition.TaskInstanceStatus.FAILED.name().equalsIgnoreCase(statusDTO.getStatus())) {
                updateStatusFailed(taskInstance, instance, statusDTO.getExceptionMessage());
            }
            transactionManager.commit(status);
        } catch (Exception e) {
            transactionManager.rollback(status);
        }
    }

    private void updateStatusFailed(final SagaTaskInstance taskInstance, final SagaInstance instance, final String exeMsg) {
        if (taskInstance.getRetriedCount() >= taskInstance.getMaxRetryCount()) {
            taskInstance.setStatus(SagaDefinition.TaskInstanceStatus.FAILED.name());
            taskInstance.setExceptionMessage(exeMsg);
            taskInstance.setInstanceLock(null);
            taskInstance.setActualEndTime(new Date());
            if (taskInstanceMapper.updateByPrimaryKey(taskInstance) != 1) {
                throw new FeignException(DB_ERROR);
            }
            instance.setStatus(SagaDefinition.InstanceStatus.FAILED.name());
            instance.setEndTime(new Date());
            instanceMapper.updateByPrimaryKeySelective(instance);
            if (instance.getCreatedBy() != 0) {
                noticeService.sendSagaFailNotice(instance);
            }
        } else {
            taskInstanceMapper.increaseRetriedCount(taskInstance.getId());
        }
    }

    private void updateStatusCompleted(final SagaTaskInstance taskInstance, final String outputData, final SagaInstance instance) {
        taskInstance.setStatus(SagaDefinition.TaskInstanceStatus.COMPLETED.name());
        if (!StringUtils.isEmpty(outputData)) {
            JsonData data = new JsonData(outputData);
            if (jsonDataMapper.insertSelective(data) != 1) {
                throw new FeignException(DB_ERROR);
            }
            taskInstance.setOutputDataId(data.getId());
        }
        taskInstance.setActualEndTime(new Date());
        if (taskInstanceMapper.updateByPrimaryKeySelective(taskInstance) != 1) {
            throw new FeignException(DB_ERROR);
        }
        Map<Integer, List<SagaTaskInstance>> seqTaskListMap = taskInstanceMapper
                .select(new SagaTaskInstance(taskInstance.getSagaInstanceId()))
                .stream().collect(groupingBy(SagaTaskInstance::getSeq));
        long unFinishedCount = seqTaskListMap.get(taskInstance.getSeq()).stream()
                .filter(t -> !SagaDefinition.InstanceStatus.COMPLETED.name().equals(t.getStatus())).count();
        if (unFinishedCount > 0) {
            return;
        }
        startNextTaskInstance(seqTaskListMap, taskInstance, instance);
    }

    private void startNextTaskInstance(final Map<Integer, List<SagaTaskInstance>> seqTaskListMap, final SagaTaskInstance taskInstance, final SagaInstance instance) {
        try {
            final JsonData temp = new JsonData();
            final List<SagaTaskInstance> seqTaskInstances = seqTaskListMap.get(taskInstance.getSeq());
            String nextInputJson = ConvertUtils.jsonMerge(ConvertUtils.convertToJsonMerge(seqTaskInstances, jsonDataMapper), objectMapper);
            if (nextInputJson != null) {
                JsonData nextInputData = new JsonData(nextInputJson);
                if (jsonDataMapper.insertSelective(nextInputData) != 1) {
                    throw new FeignException(DB_ERROR);
                }
                temp.setId(nextInputData.getId());
            }

            List<SagaTaskInstance> nextTaskInstances = getNextTaskInstances(seqTaskListMap, taskInstance.getSeq());
            if (nextTaskInstances.isEmpty()) {
                instance.setStatus(SagaDefinition.InstanceStatus.COMPLETED.name());
                instance.setEndTime(new Date());
                instance.setOutputDataId(temp.getId());
                if (instanceMapper.updateByPrimaryKeySelective(instance) != 1) {
                    throw new FeignException("error.updateStatusCompleted.updateInstanceFailed");
                }
                return;
            }
            nextTaskInstances.forEach(t -> {
                t.setStatus(SagaDefinition.TaskInstanceStatus.RUNNING.name());
                t.setInputDataId(temp.getId());
                taskInstanceMapper.updateByPrimaryKeySelective(t);
            });
        } catch (IOException e) {
            throw new FeignException("json merge error", e);
        }

    }

    private List<SagaTaskInstance> getNextTaskInstances(final Map<Integer, List<SagaTaskInstance>> integerListMap, final int currentSeq) {
        for (Map.Entry<Integer, List<SagaTaskInstance>> entry : integerListMap.entrySet()) {
            if (entry.getKey() > currentSeq) {
                return entry.getValue();
            }
        }
        return Collections.emptyList();
    }


    @Override
    public void unlockByInstance(String instance) {
        try {
            taskInstanceMapper.unlockByInstance(instance);
        } catch (Exception e) {
            LOGGER.warn("error.unlockByInstance {}, cause {}", instance, e);
        }
    }

    @Override
    @Transactional
    public void retry(long id) {
        SagaTaskInstance taskInstance = taskInstanceMapper.selectByPrimaryKey(id);
        if (taskInstance == null) {
            throw new CommonException(ERROR_CODE_TASK_INSTANCE_NOT_EXIST);
        }
        SagaInstance sagaInstance = instanceMapper.selectByPrimaryKey(taskInstance.getSagaInstanceId());
        if (sagaInstance == null) {
            throw new CommonException("error.sagaInstance.notExist");
        }
        sagaInstance.setStatus(SagaDefinition.InstanceStatus.RUNNING.name());
        sagaInstance.setEndTime(null);
        instanceMapper.updateByPrimaryKey(sagaInstance);
        taskInstance.setStatus(SagaDefinition.TaskInstanceStatus.RUNNING.name());
        taskInstanceMapper.updateByPrimaryKeySelective(taskInstance);
    }

    @Override
    public void unlockById(long id) {
        SagaTaskInstance taskInstance = taskInstanceMapper.selectByPrimaryKey(id);
        if (taskInstance == null) {
            throw new CommonException(ERROR_CODE_TASK_INSTANCE_NOT_EXIST);
        }
        taskInstance.setInstanceLock(null);
        taskInstanceMapper.updateByPrimaryKey(taskInstance);
    }


    @Override
    public ResponseEntity<Page<SagaTaskInstanceInfoDTO>> pageQuery(PageRequest pageRequest, String sagaInstanceCode, String status, String taskInstanceCode, String params, String level, Long sourceId) {
        return new ResponseEntity<>(PageHelper.doPageAndSort(pageRequest,
                () -> taskInstanceMapper.fulltextSearchTaskInstance(sagaInstanceCode, status, taskInstanceCode, params, level, sourceId)), HttpStatus.OK);
    }
}
