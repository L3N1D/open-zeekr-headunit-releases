package com.lenid.zoh.aap.protocol.messages

import com.lenid.zoh.aap.AapMessage
import com.lenid.zoh.aap.protocol.Channel
import com.lenid.zoh.aap.protocol.proto.Sensors
import com.google.protobuf.Message

open class SensorEvent(val sensorType: Int, proto: Message)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, proto)
