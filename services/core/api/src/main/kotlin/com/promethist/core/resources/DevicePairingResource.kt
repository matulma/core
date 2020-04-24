package com.promethist.core.resources

import com.promethist.core.model.DevicePairing
import io.swagger.annotations.Api
import io.swagger.annotations.ApiParam
import io.swagger.annotations.Authorization
import javax.ws.rs.*
import javax.ws.rs.core.MediaType

@Api(tags = ["Device Pairing"], authorizations = [Authorization("Authorization")])
@Path("/devicePairing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface DevicePairingResource {

    @PUT
    @Path("/pair")
    fun pairDevice(@ApiParam(required = true) @QueryParam("pairingCode") pairingCode: String): DevicePairing

    fun getDevicePairing(deviceId: String): DevicePairing?

    fun createOrUpdateDevicePairing(devicePairing: DevicePairing)
}