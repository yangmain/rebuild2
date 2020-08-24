/*
rebuild - Building your business-systems freely.
Copyright (C) 2018-2019 devezhao <zhaofang123@gmail.com>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package com.rebuild.web.general;

import cn.devezhao.persist4j.Entity;
import cn.devezhao.persist4j.Field;
import cn.devezhao.persist4j.engine.ID;
import com.alibaba.fastjson.JSON;
import com.rebuild.core.RebuildApplication;
import com.rebuild.core.configuration.general.ClassificationManager;
import com.rebuild.core.configuration.general.MultiSelectManager;
import com.rebuild.core.configuration.general.PickListManager;
import com.rebuild.core.helper.state.StateManager;
import com.rebuild.core.metadata.MetadataHelper;
import com.rebuild.core.metadata.impl.DisplayType;
import com.rebuild.core.metadata.impl.EasyMeta;
import com.rebuild.web.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 下拉列表型字段值列表
 *
 * @author ZHAO
 * @since 2019/12/1
 */
@Controller
@RequestMapping("/commons/metadata/")
public class PicklistDataControll extends BaseController {

    // for PickList/State
    @RequestMapping({"picklist", "field-options"})
    public void fetchPicklist(HttpServletRequest request, HttpServletResponse response) {
        String entity = getParameterNotNull(request, "entity");
        String field = getParameterNotNull(request, "field");

        Field fieldMeta = getRealField(entity, field);
        DisplayType dt = EasyMeta.getDisplayType(fieldMeta);

        JSON options;
        if (dt == DisplayType.STATE) {
            options = StateManager.instance.getStateOptions(fieldMeta);
        } else if (dt == DisplayType.MULTISELECT) {
            options = MultiSelectManager.instance.getSelectList(fieldMeta);
        } else {
            options = PickListManager.instance.getPickList(fieldMeta);
        }
        writeSuccess(response, options);
    }

    // for Classification
    @RequestMapping("classification")
    public void fetchClassification(HttpServletRequest request, HttpServletResponse response) {
        String entity = getParameterNotNull(request, "entity");
        String field = getParameterNotNull(request, "field");

        Field fieldMeta = getRealField(entity, field);
        ID useClassification = ClassificationManager.instance.getUseClassification(fieldMeta, true);
        if (useClassification == null) {
            writeFailure(response, "分类字段配置有误");
            return;
        }

        ID parent = getIdParameter(request, "parent");
        String sql = "select itemId,name from ClassificationData where dataId = ? and isHide = 'F' and ";
        if (parent != null) {
            sql += "parent = '" + parent + "'";
        } else {
            sql += "parent is null";
        }
        sql += " order by code, name";
        Object[][] data = RebuildApplication.createQueryNoFilter(sql)
                .setParameter(1, useClassification)
                .setLimit(500)  // 最多显示
                .array();
        writeSuccess(response, data);
    }

    private Field getRealField(String entity, String fieldPath) {
        Entity entityMeta = MetadataHelper.getEntity(entity);
        return MetadataHelper.getLastJoinField(entityMeta, fieldPath);
    }
}
