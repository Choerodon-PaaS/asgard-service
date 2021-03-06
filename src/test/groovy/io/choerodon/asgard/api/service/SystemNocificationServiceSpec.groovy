package io.choerodon.asgard.api.service

import io.choerodon.asgard.IntegrationTestConfiguration
import io.choerodon.asgard.api.dto.ScheduleTaskInstanceLogDTO
import io.choerodon.asgard.api.dto.SystemNotificationCreateDTO
import io.choerodon.asgard.api.dto.SystemNotificationDTO
import io.choerodon.asgard.api.dto.SystemNotificationUpdateDTO
import io.choerodon.asgard.api.service.impl.SystemNotificationServiceImpl
import io.choerodon.asgard.domain.QuartzTask
import io.choerodon.asgard.domain.QuartzTaskDetail
import io.choerodon.asgard.infra.mapper.QuartzTaskInstanceMapper
import io.choerodon.asgard.infra.mapper.QuartzTaskMapper
import io.choerodon.asgard.schedule.QuartzDefinition
import io.choerodon.core.domain.Page
import io.choerodon.core.iam.ResourceLevel
import io.choerodon.mybatis.pagehelper.domain.PageRequest
import io.choerodon.mybatis.pagehelper.domain.Sort
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import spock.lang.Specification

import static java.lang.System.currentTimeMillis
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(IntegrationTestConfiguration)
class SystemNocificationServiceSpec extends Specification {


    private SystemNocificationService systemNocificationService
    private ScheduleMethodService scheduleMethodService = Mock(ScheduleMethodService)
    private ScheduleTaskService scheduleTaskService = Mock(ScheduleTaskService)
    private QuartzTaskInstanceMapper instanceMapper = Mock(QuartzTaskInstanceMapper)
    private QuartzTaskMapper quartzTaskMapper = Mock(QuartzTaskMapper)

    void setup() {
        systemNocificationService = new SystemNotificationServiceImpl(scheduleMethodService, scheduleTaskService, instanceMapper, quartzTaskMapper)
    }

    def "Create"() {
        given: "参数准备"
        def level = ResourceLevel.ORGANIZATION
        def dto = new SystemNotificationCreateDTO()
        dto.setContent("content")
        dto.setStartTime(new Date())
        def userId = 1L
        def sourceId = 1L
        def quartzTask = new QuartzTask()
        quartzTask.setId(1L)
        and: "mock"
        scheduleMethodService.getMethodIdByCode(_) >> { return 1L }
        scheduleTaskService.create(_, _, _) >> { return quartzTask }
        quartzTaskMapper.updateByPrimaryKeySelective(_) >> 1
        when: "方法调用"
        systemNocificationService.create(level, dto, userId, sourceId)
        then: "结果比较"
        noExceptionThrown()
    }

    def "Update"() {
        given: "参数准备"
        def level = ResourceLevel.SITE
        def dto = new SystemNotificationUpdateDTO()
        dto.setContent("content")
        dto.setObjectVersionNumber(1L)
        def sourceId = 1L
        def quartzTask = new QuartzTaskDetail()
        quartzTask.setId(1L)
        quartzTask.setStartTime(new Date(currentTimeMillis() + 10000))
        and: "mock"
        quartzTaskMapper.selectTaskById(_) >> quartzTask
        quartzTaskMapper.updateByPrimaryKeySelective(_) >> 1
        when: "方法调用"
        systemNocificationService.update(dto, level, sourceId)
        then: "结果比较"
        noExceptionThrown()
    }

    def "Update2"() {
        given: "参数准备"
        def level = ResourceLevel.SITE
        def dto = new SystemNotificationUpdateDTO()
        //taskId为null，因为delete方法中参数为long,Long转为null会报空指针异常
        dto.setTaskId(1L)
        dto.setContent("content")
        dto.setStartTime(new Date())
        dto.setObjectVersionNumber(2L)
        def sourceId = 1L
        def quartzTask = new QuartzTaskDetail()
        quartzTask.setId(1L)
        quartzTask.setStartTime(new Date(currentTimeMillis() + 10000))
        and: "mock"
        quartzTaskMapper.selectTaskById(_) >> quartzTask
        scheduleMethodService.getMethodIdByCode(_) >> { return 1L }
        scheduleTaskService.create(_, _, _) >> new QuartzTask()
        when: "方法调用"
        systemNocificationService.update(dto, level, sourceId)
        then: "结果比较"
        noExceptionThrown()
    }

    def "GetDetailById"() {
        given: "参数准备"
        def level = ResourceLevel.ORGANIZATION
        def taskId = 1L
        def sourceId = 1L

        def quartzTask = new QuartzTask()
        quartzTask.setExecuteParams("{\"content\":\"系统公告测试kkkk\"}")
        quartzTask.setStartTime(new Date())

        def list = new ArrayList<ScheduleTaskInstanceLogDTO>()
        def logdto1 = new ScheduleTaskInstanceLogDTO()
        logdto1.setStatus(QuartzDefinition.InstanceStatus.COMPLETED.name())
        list.add(logdto1)
        and: "mock"
        scheduleTaskService.getQuartzTask(_, _, _) >> { return quartzTask }
        instanceMapper.selectByTaskId(taskId, _, _, _, _, _) >> { return list }
        when: "方法调用"
        systemNocificationService.getDetailById(level, taskId, sourceId)
        then: "结果比对"
        noExceptionThrown()
    }

    def "PagingAll"() {
        given: "参数准备"
        def status = null
        def content = "content"
        def params = null
        def level = ResourceLevel.SITE
        def sourceId = 0L

        and: "构造pageRequest"
        def order = new Sort.Order("start_time")
        def pageRequest = new PageRequest(1, 2, new Sort(order))

        and: "mock"
        scheduleTaskService.pagingAllNotification(_, _, _, _, _, _) >> {
            def list = new ArrayList<SystemNotificationDTO>()
            def entity1 = new SystemNotificationDTO(1, "{\"content\":\"系统公告测试kkkk\"}",
                    new Date(), SystemNotificationDTO.NotificationStatus.COMPLETED.value())
            list.add(entity1)
            def page = new Page()
            page.setContent(list)
            return page
        }

        when: "方法调用"
        systemNocificationService.pagingAll(pageRequest, status, content, params, level, sourceId)
        then: "结果分析"
        noExceptionThrown()
    }
}