/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <sekai@neko.services>                    *
 * Copyright (C) 2021 by Max Lv <max.c.lv@gmail.com>                          *
 * Copyright (C) 2021 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.pingtunnel;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import cn.hutool.core.util.StrUtil;
import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class PingTunnelBean extends AbstractBean {

    public String key;

    @Override
    public void initDefaultValues() {
        super.initDefaultValues();
        if (key == null) key = "";
    }

    @Override
    public String displayName() {
        if (StrUtil.isNotBlank(name)) {
            return name;
        } else {
            return serverAddress;
        }
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        output.writeString(serverAddress);
        output.writeString(key);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        serverAddress = input.readString();
        key = input.readString();
        initDefaultValues();
    }

    @NotNull
    @Override
    public PingTunnelBean clone() {
        return KryoConverters.deserialize(new PingTunnelBean(), KryoConverters.serialize(this));
    }
}
