package com.xiaomi.mone.tpc.common.param;

import com.xiaomi.mone.tpc.common.enums.AccountTypeEnum;
import lombok.Data;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

/**
 * @project: mi-tpc
 * @author: zgf1
 * @date: 2022/3/3 19:52
 */
@Data
@ToString(callSuper = true)
public class LoginSessionParam extends BaseParam {

    private Integer type;
    private String password;
    private String pageUrl;

    @Override
    public boolean argCheck() {
        if (StringUtils.isBlank(getAccount())) {
            return false;
        }
        if (type == null || AccountTypeEnum.getEnum(type) == null) {
            return false;
        }
        if (StringUtils.isBlank(password)) {
            return false;
        }
        if (StringUtils.isBlank(pageUrl)) {
            return false;
        }
        return true;
    }

}
