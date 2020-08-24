/*
Copyright (c) REBUILD <https://getrebuild.com/>. All rights reserved.

rebuild is dual-licensed under commercial and open source licenses (GPLv3).
See LICENSE and COMMERCIAL in the project root for license information.
*/

package com.rebuild.core.service.feeds;

import cn.devezhao.commons.CalendarUtils;
import cn.devezhao.commons.RegexUtils;
import cn.devezhao.persist4j.engine.ID;
import com.alibaba.fastjson.JSON;
import com.rebuild.core.RebuildApplication;
import com.rebuild.core.helper.DistributedJobBean;
import com.rebuild.core.helper.RebuildConfiguration;
import com.rebuild.core.helper.SMSender;
import com.rebuild.core.service.notification.Message;
import com.rebuild.core.service.notification.MessageBuilder;
import com.rebuild.utils.AppUtils;
import org.quartz.JobExecutionException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * @author devezhao
 * @since 2020/2/27
 */
@Component
public class FeedsScheduleJob extends DistributedJobBean {

    @Scheduled(cron = "0 * * * * ?")
    @Override
    protected void executeInternalSafe() throws JobExecutionException {
        Calendar time = CalendarUtils.getInstance();
        time.set(Calendar.SECOND, 0);
        time.set(Calendar.MILLISECOND, 0);

        Object[][] array = RebuildApplication.createQueryNoFilter(
                "select createdBy,feedsId,content,contentMore from Feeds where scheduleTime = ? and type = ?")
                .setParameter(1, time.getTime())
                .setParameter(2, FeedsType.SCHEDULE.getMask())
                .array();

        if (array.length > 0) {
            doInternal(array);
        }
    }

    /**
     * @param array
     */
    protected void doInternal(Object[][] array) {
        // 合并同用户的多条消息
        Map<ID, List<Object[]>> map = new HashMap<>();
        for (Object[] o : array) {
            int reminds = JSON.parseObject((String) o[3]).getIntValue("scheduleRemind");
            if (reminds == 0) {
                continue;
            }

            List<Object[]> list = map.computeIfAbsent((ID) o[0], k -> new ArrayList<>());
            list.add(o);
        }

        // 发送
        for (List<Object[]> list : map.values()) {
            List<Object[]> notifications = new ArrayList<>();
            List<Object[]> emails = new ArrayList<>();
            List<Object[]> smss = new ArrayList<>();

            // 分类
            for (Object[] o : list) {
                int reminds = JSON.parseObject((String) o[3]).getIntValue("scheduleRemind");
                if ((reminds & 1) != 0) notifications.add(o);
                if ((reminds & 2) != 0) emails.add(o);
                if ((reminds & 4) != 0) smss.add(o);
            }

            final ID toUser = (ID) list.get(0)[0];
            final String subjectTemp = "你有 %d 条动态日程提醒";

            // 消息通知
            if (!notifications.isEmpty()) {
                String subject = String.format(subjectTemp, notifications.size());
                String contents = subject + mergeContents(notifications, false);
                RebuildApplication.getNotifications().send(
                        MessageBuilder.createMessage(toUser, contents, Message.TYPE_FEEDS));
            }

            // 邮件
            final String emailAddr = RebuildApplication.getUserStore().getUser(toUser).getEmail();
            if (SMSender.availableMail() && RegexUtils.isEMail(emailAddr) && !emails.isEmpty()) {
                String subject = String.format(subjectTemp, emails.size());
                String contents = mergeContents(emails, true);
                contents = MessageBuilder.formatMessage(contents, true, false);
                SMSender.sendMailAsync(emailAddr, subject, contents);
            }

            // 短信（考虑短信字数，内容简化了）
            final String mobileAddr = RebuildApplication.getUserStore().getUser(toUser).getWorkphone();
            if (SMSender.availableSMS() && RegexUtils.isCNMobile(mobileAddr) && !smss.isEmpty()) {
                String subject = String.format(subjectTemp, smss.size());
                SMSender.sendSMSAsync(mobileAddr, subject);
            }
        }
    }

    /**
     * @param msgs
     * @param fullUrl
     * @return
     */
    protected String mergeContents(List<Object[]> msgs, boolean fullUrl) {
        StringBuilder sb = new StringBuilder();
        int num = 0;
        for (Object[] o : msgs) {
            sb.append("\n- [");

            String c = (String) o[2];
            if (c.length() > 100) {
                c = c.substring(0, 100) + " ...";
            }
            sb.append(c);

            String url = "/app/list-and-view?id=" + o[1];
            if (fullUrl) {
                url = RebuildConfiguration.getHomeUrl(url);
            } else {
                url = AppUtils.getContextPath() + url;
            }
            sb.append("](").append(url).append(")");

            num++;
            // 最多列出 N 条
            if (num >= 5) {
                break;
            }
        }

        if (msgs.size() > num) {
            sb.append("\n- 等共计 ").append(msgs.size()).append(" 条");
        }

        return sb.toString();
    }
}
