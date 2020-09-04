/*
Copyright (c) REBUILD <https://getrebuild.com/> and/or its owners. All rights reserved.

rebuild is dual-licensed under commercial and open source licenses (GPLv3).
See LICENSE and COMMERCIAL in the project root for license information.
*/

package com.rebuild.core;

import cn.devezhao.bizz.security.AccessDeniedException;
import cn.devezhao.commons.CalendarUtils;
import cn.devezhao.commons.excel.Cell;
import cn.devezhao.persist4j.PersistManagerFactory;
import cn.devezhao.persist4j.Query;
import cn.devezhao.persist4j.engine.ID;
import cn.devezhao.persist4j.engine.StandardRecord;
import cn.devezhao.persist4j.query.QueryedRecord;
import com.alibaba.fastjson.serializer.SerializeConfig;
import com.alibaba.fastjson.serializer.ToStringSerializer;
import com.rebuild.core.cache.CommonsCache;
import com.rebuild.core.metadata.DynamicMetadataFactory;
import com.rebuild.core.privileges.PrivilegesManager;
import com.rebuild.core.privileges.RecordOwningCache;
import com.rebuild.core.privileges.UserStore;
import com.rebuild.core.service.CommonsService;
import com.rebuild.core.service.ServiceSpec;
import com.rebuild.core.service.SqlExecutor;
import com.rebuild.core.service.general.EntityService;
import com.rebuild.core.service.general.GeneralEntityService;
import com.rebuild.core.service.notification.NotificationService;
import com.rebuild.core.service.query.QueryFactory;
import com.rebuild.core.support.ConfigurationItem;
import com.rebuild.core.support.License;
import com.rebuild.core.support.RebuildConfiguration;
import com.rebuild.core.support.i18n.Language;
import com.rebuild.core.support.i18n.LanguageBundle;
import com.rebuild.core.support.setup.Installer;
import com.rebuild.core.support.setup.UpgradeDatabase;
import com.rebuild.utils.JSONable;
import com.rebuild.utils.codec.RbDateCodec;
import com.rebuild.utils.codec.RbRecordCodec;
import com.rebuild.web.OnlineSessionStore;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.h2.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ImportResource;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * 后台类入口
 *
 * @author zhaofang123@gmail.com
 * @since 05/18/2018
 */
@Repository
@SpringBootApplication(scanBasePackages = {"com.rebuild"}, exclude = {
        DataSourceAutoConfiguration.class, JdbcRepositoriesAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
        RedisAutoConfiguration.class, CacheAutoConfiguration.class})
@ImportResource("classpath:application-bean.xml")
public class Application {

    protected static final Logger LOG = LoggerFactory.getLogger(Application.class);

    /**
     * Rebuild Version
     */
    public static final String VER = "2.0.0-SNAPSHOT";
    /**
     * Rebuild Build
     */
    public static final int BUILD = 20000;

    static {
        // Driver for DB
        try {
            Class.forName(com.mysql.jdbc.Driver.class.getName());
            Class.forName(Driver.class.getName());
        } catch (ClassNotFoundException ex) {
            throw new RebuildException(ex);
        }

        // for fastjson Serialize
        SerializeConfig.getGlobalInstance().put(JSONable.class, ToStringSerializer.instance);
        SerializeConfig.getGlobalInstance().put(ID.class, ToStringSerializer.instance);
        SerializeConfig.getGlobalInstance().put(Cell.class, ToStringSerializer.instance);
        SerializeConfig.getGlobalInstance().put(Date.class, RbDateCodec.instance);
        SerializeConfig.getGlobalInstance().put(StandardRecord.class, RbRecordCodec.instance);
        SerializeConfig.getGlobalInstance().put(QueryedRecord.class, RbRecordCodec.instance);
    }

    // SPRING
    private static ConfigurableApplicationContext APPLICATION_CONTEXT;

    // 实体对应的服务类
    private static Map<Integer, ServiceSpec> ESS = null;

    private static boolean serversReady;

    private static Date startupTime;

    private static WebApplicationType startupType;

    public static void main(String[] args) {
        if (APPLICATION_CONTEXT != null) throw new IllegalStateException("Rebuild already started");

        long time = System.currentTimeMillis();

        LOG.info("Initializing SpringBoot context ...");
        SpringApplication spring = new SpringApplication(Application.class);
        spring.setBannerMode(Banner.Mode.OFF);
        if (startupType != null) {
            spring.setWebApplicationType(WebApplicationType.NONE);
        }

        APPLICATION_CONTEXT = spring.run(args);

        String localUrl = String.format("http://localhost:%s%s",
                RebuildEnvironmentPostProcessor.getProperty("server.port", "8080"),
                RebuildEnvironmentPostProcessor.getProperty("server.servlet.context-path", ""));

        boolean success = false;
        try {
            if (Installer.isInstalled()) {
                success = init();

                if (success) {
                    time = System.currentTimeMillis() - time;
                    LOG.info("Rebuild boot successful in " + time + " ms. Local URL : " + localUrl);
                }

            } else {
                LOG.info(formatBootMsg("REBUILD IS WAITING FOR INSTALL ...", "Install URL : " + localUrl));
            }

        } catch (Exception ex) {
            serversReady = false;
            LOG.error(formatBootMsg("REBUILD BOOTING FILAED !!!"), ex);

        } finally {
            if (!success) {
                // 失败加载语言包
                try {
                    APPLICATION_CONTEXT.getBean(Language.class).init();
                } catch (Exception ex) {
                    LOG.error(null, ex);
                }
            }

            startupTime = CalendarUtils.now();
        }
    }

    /**
     * 系统初始化
     *
     * @throws Exception
     */
    public static boolean init() throws Exception {
        LOG.info("Initializing Rebuild context ...");

        if (!(serversReady = ServerStatus.checkAll())) {
            LOG.error(formatBootMsg(
                    "REBUILD BOOTING FAILURE DURING THE STATUS CHECK.", "PLEASE VIEW LOGS FOR MORE DETAILS."));
            return false;
        }

        // 升级数据库
        UpgradeDatabase.getInstance().upgradeQuietly();

        // 刷新配置缓存
        for (ConfigurationItem item : ConfigurationItem.values()) {
            RebuildConfiguration.get(item, true);
        }

        // 加载自定义实体
        LOG.info("Loading customized/business entities ...");
        ((DynamicMetadataFactory) APPLICATION_CONTEXT.getBean(PersistManagerFactory.class).getMetadataFactory()).refresh(false);

        // 实体对应的服务类
        ESS = new HashMap<>();
        for (Map.Entry<String, ServiceSpec> e : APPLICATION_CONTEXT.getBeansOfType(ServiceSpec.class).entrySet()) {
            ServiceSpec ss = e.getValue();
            if (ss.getEntityCode() > 0) {
                ESS.put(ss.getEntityCode(), ss);
                LOG.info("Service specification : " + ss.getClass().getName() + " for <" + ss.getEntityCode() + ">");
            }
        }

        // 初始化业务
        for (Initialization bean : APPLICATION_CONTEXT.getBeansOfType(Initialization.class).values()) {
            bean.init();
        }

        APPLICATION_CONTEXT.registerShutdownHook();

        LOG.info("REBUILD AUTHORITY : " + StringUtils.join(License.queryAuthority().values(), " | "));

        return true;
    }

    private static String formatBootMsg(String... msgs) {
        List<String> banners = new ArrayList<>();
        CollectionUtils.addAll(banners, msgs);
        banners.add("\n  Version : " + VER);
        banners.add("OS      : " + SystemUtils.OS_NAME + " (" + SystemUtils.OS_ARCH + ")");
        banners.add("JVM     : " + SystemUtils.JAVA_VM_NAME + " (" + SystemUtils.JAVA_VERSION + ")");
        banners.add("\n  Report an issue :");
        banners.add("https://getrebuild.com/report-issue?title=boot");

        return "\n\n###################################################################\n\n  "
                + StringUtils.join(banners, "\n  ") +
                "\n\n###################################################################\n";
    }

    public static void debug(WebApplicationType type, String ... args) {
        if (type != null) startupType = type;
        System.setProperty("rbdev", "true");

        Application.main(args);
    }

    public static boolean devMode() {
        return BooleanUtils.toBoolean(System.getProperty("rbdev"));
    }

    public static boolean serversReady() {
        return serversReady && APPLICATION_CONTEXT != null;
    }

    public static Date getStartupTime() {
        return startupTime;
    }

    public static ConfigurableApplicationContext getApplicationContext() {
        if (APPLICATION_CONTEXT == null) throw new IllegalStateException("Rebuild unstarted");
        return APPLICATION_CONTEXT;
    }

    public static <T> T getBean(Class<T> beanClazz) {
        return getApplicationContext().getBean(beanClazz);
    }

    public static OnlineSessionStore getSessionStore() {
        return getBean(OnlineSessionStore.class);
    }

    public static ID getCurrentUser() throws AccessDeniedException {
        return getSessionStore().get();
    }

    public static LanguageBundle getCurrentBundle() {
        return getBean(Language.class).getCurrentBundle();
    }

    public static PersistManagerFactory getPersistManagerFactory() {
        return getBean(PersistManagerFactory.class);
    }

    public static DynamicMetadataFactory getMetadataFactory() {
        return (DynamicMetadataFactory) getPersistManagerFactory().getMetadataFactory();
    }

    public static UserStore getUserStore() {
        return getBean(UserStore.class);
    }

    public static RecordOwningCache getRecordOwningCache() {
        return getBean(RecordOwningCache.class);
    }

    public static CommonsCache getCommonsCache() {
        return getBean(CommonsCache.class);
    }

    public static PrivilegesManager getPrivilegesManager() {
        return getBean(PrivilegesManager.class);
    }

    public static QueryFactory getQueryFactory() {
        return getBean(QueryFactory.class);
    }

    public static Query createQuery(String ajql) {
        return getQueryFactory().createQuery(ajql);
    }

    public static Query createQuery(String ajql, ID user) {
        return getQueryFactory().createQuery(ajql, user);
    }

    public static Query createQueryNoFilter(String ajql) {
        return getQueryFactory().createQueryNoFilter(ajql);
    }

    public static SqlExecutor getSqlExecutor() {
        return getBean(SqlExecutor.class);
    }

    public static ServiceSpec getService(int entityCode) {
        if (ESS != null && ESS.containsKey(entityCode)) {
            return ESS.get(entityCode);
        } else {
            return getGeneralEntityService();
        }
    }

    public static EntityService getEntityService(int entityCode) {
        ServiceSpec es = getService(entityCode);
        if (EntityService.class.isAssignableFrom(es.getClass())) {
            return (EntityService) es;
        }
        throw new RebuildException("Non EntityService implements : " + entityCode);
    }

    public static GeneralEntityService getGeneralEntityService() {
        return (GeneralEntityService) getApplicationContext().getBean("generalEntityService");
    }

    public static CommonsService getCommonsService() {
        return getBean(CommonsService.class);
    }

    public static NotificationService getNotifications() {
        return getBean(NotificationService.class);
    }
}
