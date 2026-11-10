package com.lenid.zoh.aap.protocol.messages

import com.lenid.zoh.aap.AapMessage
import com.lenid.zoh.aap.protocol.Channel
import com.lenid.zoh.aap.protocol.proto.Sensors
import com.google.protobuf.Message

class DrivingStatusEvent(status: Sensors.SensorBatch.DrivingStatusData.Status)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, makeProto(status)) {

    companion object {
        private fun makeProto(status: Sensors.SensorBatch.DrivingStatusData.Status): Message {
            return Sensors.SensorBatch.newBuilder()
                    .addDrivingStatus(Sensors.SensorBatch.DrivingStatusData.newBuilder()
                            .setStatus(status.number))
                    .build()
        }
    }
}
