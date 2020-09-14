/*
Copyright (c) REBUILD <https://getrebuild.com/> and/or its owners. All rights reserved.

rebuild is dual-licensed under commercial and open source licenses (GPLv3).
See LICENSE and COMMERCIAL in the project root for license information.
*/

package com.rebuild.web.admin.bizz;

import cn.devezhao.bizz.security.member.Team;
import cn.devezhao.commons.web.ServletUtils;
import cn.devezhao.persist4j.engine.ID;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.rebuild.core.Application;
import com.rebuild.core.configuration.general.DataListManager;
import com.rebuild.core.privileges.TeamService;
import com.rebuild.core.privileges.UserHelper;
import com.rebuild.core.privileges.bizz.User;
import com.rebuild.web.EntityController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.*;

/**
 * @author devezhao
 * @since 2019/11/13
 */
@Controller
@RequestMapping("/admin/bizuser/")
public class TeamControl extends EntityController {

    @RequestMapping("teams")
    public ModelAndView pageList(HttpServletRequest request) {
        ID user = getRequestUser(request);
        ModelAndView mv = createModelAndView("/admin/bizuser/team-list", "Team", user);
        JSON config = DataListManager.instance.getFieldsLayout("Team", user);
        mv.getModel().put("DataListConfig", JSON.toJSONString(config));
        return mv;
    }

    @RequestMapping(value = "team-members", method = RequestMethod.GET)
    public void getMembers(HttpServletRequest request, HttpServletResponse response) {
        ID teamId = getIdParameterNotNull(request, "team");
        Team team = Application.getUserStore().getTeam(teamId);

        List<Object[]> members = new ArrayList<>();
        for (Principal p : team.getMembers()) {
            User user = (User) p;
            members.add(new Object[]{
                    user.getId(), user.getFullName(),
                    user.getOwningDept() != null ? user.getOwningDept().getName() : null
            });
        }
        writeSuccess(response, members);
    }

    @RequestMapping(value = "team-members-add", method = RequestMethod.POST)
    public void addMembers(HttpServletRequest request, HttpServletResponse response) {
        final ID teamId = getIdParameterNotNull(request, "team");

        JSON usersDef = ServletUtils.getRequestJson(request);
        Set<ID> users = UserHelper.parseUsers((JSONArray) usersDef, null);

        if (!users.isEmpty()) {
            Application.getBean(TeamService.class).createMembers(teamId, users);
        }
        writeSuccess(response);
    }

    @RequestMapping(value = "team-members-del", method = RequestMethod.POST)
    public void deleteMembers(HttpServletRequest request, HttpServletResponse response) {
        ID teamId = getIdParameterNotNull(request, "team");
        ID userId = getIdParameterNotNull(request, "user");

        Application.getBean(TeamService.class).deleteMembers(teamId, Collections.singletonList(userId));
        writeSuccess(response);
    }
}
