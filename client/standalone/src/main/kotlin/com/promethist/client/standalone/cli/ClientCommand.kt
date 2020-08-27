package com.promethist.client.standalone.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.type.TypeReference
import com.pi4j.io.gpio.GpioFactory
import com.pi4j.io.gpio.PinPullResistance
import com.pi4j.io.gpio.PinState
import com.pi4j.io.gpio.RaspiPin
import com.pi4j.io.gpio.event.GpioPinListenerDigital
import com.promethist.client.BotClient
import com.promethist.client.BotConfig
import com.promethist.client.BotContext
import com.promethist.client.client.JwsBotClientSocket
import com.promethist.client.common.WavFileAudioRecorder
import com.promethist.client.common.OkHttp3BotClientSocket
import com.promethist.client.signal.SignalProcessor
import com.promethist.client.signal.SignalProvider
import com.promethist.client.standalone.Application
import com.promethist.client.standalone.DeviceClientCallback
import com.promethist.client.standalone.io.*
import com.promethist.client.standalone.ui.Screen
import com.promethist.client.util.InetInterface
import com.promethist.common.AppConfig
import com.promethist.common.ObjectUtil.defaultMapper
import com.promethist.common.ServiceUrlResolver
import com.promethist.core.model.SttConfig
import com.promethist.core.model.Voice
import com.promethist.core.type.Dynamic
import com.promethist.core.type.PropertyMap
import cz.alry.jcommander.CommandRunner
import org.slf4j.LoggerFactory
import java.awt.Color
import java.io.*
import java.util.*
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class ClientCommand: CommandRunner<Application.Params, ClientCommand.Params> {

    enum class BotSocketType { OkHttp3, JWS }

    @Parameters(commandNames = ["client"], commandDescription = "Run client (press Ctrl+C to quit)")
    class Params : ClientParams() {

        @Parameter(names = ["-c", "--config-file"], order = 0, description = "Config file")
        var configFile: String? = null

        @Parameter(names = ["-d", "--device"], order = 1, description = "Device type (e.g. desktop, rpi)")
        var device = "desktop"

        @Parameter(names = ["-e", "--environment"], order = 2, description = "Environment (develop, preview) - this superseeds -u value")
        var environment: String? = null

        @Parameter(names = ["-nc", "--no-cache"], order = 3, description = "Do not cache anything")
        var noCache = false

        @Parameter(names = ["-s", "--sender"], order = 4, description = "Sender identification")
        var sender = "standalone:" + (InetInterface.getActive()?.hardwareAddress ?: "default")

        @Parameter(names = ["-it", "--intro-text"], order = 5, description = "Intro text")
        var introText: String? = null

        @Parameter(names = ["-as", "--auto-start"], order = 6, description = "Start conversation automatically")
        var autoStart = false

        @Parameter(names = ["-ex", "--exceptions"], order = 7, description = "Raise exceptions")
        var exitOnError = false

        @Parameter(names = ["-nol", "--no-output-logs"], order = 8, description = "No output logs")
        var noOutputLogs = false

        @Parameter(names = ["-log", "--show-logs"], order = 9, description = "Show contextual logs")
        var logs = false

        // audio

        @Parameter(names = ["-stt"], order = 30, description = "STT mode (Default, SingleUtterance, Duplex)")
        var sttMode = SttConfig.Mode.SingleUtterance

        @Parameter(names = ["-v", "--voice"], order = 31, description = "TTS voice")
        var voice: Voice? = null

        @Parameter(names = ["-pn", "--port-name"], order = 32, description = "Audio output port name")
        var portName: String = "SPEAKER"

        @Parameter(names = ["-vo", "--volume"], order = 33, description = "Audio output volume")
        var volume: Int? = null

        @Parameter(names = ["-nia", "--no-input-audio"], order = 34, description = "No input audio (text input only)")
        var noInputAudio = false

        @Parameter(names = ["-noa", "--no-output-audio"], order = 35, description = "No output audio (text output only)")
        var noOutputAudio = false

        @Parameter(names = ["-aru", "--audio-record-upload"], order = 36, description = "Audio record with upload (none, local, night, immediate)")
        var audioRecordUpload = WavFileAudioRecorder.UploadMode.none

        // GUI

        @Parameter(names = ["-scr", "--screen"], order = 40, description = "Screen view (none, window, fullscreen)")
        var screen = "none"

        @Parameter(names = ["-nan", "--no-animations"], order = 41, description = "No animations")
        var noAnimations = false

        // networking

        @Parameter(names = ["-sp", "--socket-ping"], order = 80, description = "Socket ping period (in seconds, 0 = do not ping)")
        var socketPing = 10L

        @Parameter(names = ["-st", "--socket-type"], order = 81, description = "Socket implementation type (okhttp3, jetty, jws)")
        var socketType = ClientCommand.BotSocketType.OkHttp3

        @Parameter(names = ["-aa", "--auto-update"], order = 82, description = "Auto update JAR file")
        var autoUpdate = false

        @Parameter(names = ["-du", "--dist-url"], order = 83, description = "Distribution URL for auto updates")
        var distUrl = "https://repository.promethist.ai/dist"

        val signalProcessor: SignalProcessor? = null
    }

    override fun run(globalParams: Application.Params, params: Params) {
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).level = Level.toLevel(globalParams.logLevel)
        val writer = OutputStreamWriter(
            if (params.output == "stdout")
                System.out
            else
                FileOutputStream(params.output)
        )
        if (params.configFile != null) {
            FileInputStream(params.configFile).use {
                defaultMapper.readerForUpdating(params).readValue(JsonFactory().createParser(it), object : TypeReference<Params>() {})
            }
            println("{Using configuration ${params.configFile} " + (if (params.signalProcessor != null) "with" else "without") + " signal processor}")
        }

        if (params.volume != null) {
            OutputAudioDevice.volume(params.portName, params.volume!!)
            writer.write("{Volume ${params.portName} set to ${params.volume}}\n")
        }
        var light: Light? = null
        val output = PrintWriter(writer, true)
        var responded = false
        val callback = object : DeviceClientCallback(
                output,
                params.distUrl,
                params.autoUpdate,
                params.noCache,
                params.noOutputAudio,
                params.noOutputLogs,
                params.portName,
                logs = params.logs
        ) {
            override fun onBotStateChange(client: BotClient, newState: BotClient.State) {
                super.onBotStateChange(client, newState)
                when (newState) {
                    BotClient.State.Listening ->
                        if (light is ColorLight)
                            (light as ColorLight)?.set(Color.GREEN)
                        else
                            light?.high()
                    BotClient.State.Processing ->
                        if (light is ColorLight)
                            (light as ColorLight)?.set(Color.BLUE)
                    BotClient.State.Failed ->
                        if (light is ColorLight)
                            (light as ColorLight)?.set(Color.RED)
                    BotClient.State.Open -> {}
                    else ->
                        light?.low()
                }
            }

            override fun onReady(client: BotClient) {
                super.onReady(client)
                light?.apply {
                    blink(0)
                    low()
                }
            }

            override fun text(client: BotClient, text: String) {
                super.text(client, text)
                responded = true
            }

            override fun onFailure(client: BotClient, t: Throwable) {
                super.onFailure(client, t)
                responded = true
            }
        }
        val attributes = Dynamic(
                "clientType" to "standalone:${AppConfig.version}",
                "clientScreen" to (params.screen != "none")
        )
        val context = BotContext(
                url = if (params.environment != null) {
                    val env = if (listOf("production", "default").contains(params!!.environment))
                        ""
                    else
                        ".${params.environment}"
                    if (params.environment == "local")
                        "http://localhost:8080" else
                        "https://port$env.promethist.com"
                } else
                    params.url,
                key = params.key,
                sender = params.sender,
                voice = params.voice,
                autoStart = params.autoStart,
                locale = Locale(params.language, Locale.getDefault().country),
                attributes = attributes
        )
        if (params.introText != null)
            context.introText = params.introText!!
        val micChannel = params.micChannel.split(':').map { it.toInt() }
        val speechDevice = SpeechDeviceFactory.getSpeechDevice(params.speechDeviceName)
        val client = BotClient(
                context,
                // web socket
                when (params.socketType) {
                    BotSocketType.JWS -> JwsBotClientSocket(context.url, params.exitOnError, params.socketPing)
                    else -> OkHttp3BotClientSocket(context.url, params.exitOnError, params.socketPing)
                },
                // input audio device
                if (params.noInputAudio)
                    null
                else
                    Microphone(speechDevice, micChannel[0], micChannel[1]),
                // bot callback
                callback,
                // TTS type
                if (params.noOutputAudio)
                    BotConfig.TtsType.None
                else
                    BotConfig.TtsType.RequiredLinks,
                // STT mode
                params.sttMode,
                // audio recorder
                if (params.noInputAudio || (params.audioRecordUpload == WavFileAudioRecorder.UploadMode.none))
                    null
                else
                    WavFileAudioRecorder(File("."),
                            if (context.url.startsWith("http://localhost"))
                                ServiceUrlResolver.getEndpointUrl("filestore", ServiceUrlResolver.RunMode.local)
                            else
                                context.url.replace("port", "filestore"),
                            params.audioRecordUpload
                    )
        )
        if (params.screen != "none") {
            Screen.client = client
            Screen.fullScreen = (params.screen == "fullscreen")
            Screen.animations = !params.noAnimations
            thread {
                Screen.launch()
            }
        }
        params.signalProcessor?.apply {
            println("{enabling signal processor}")
            emitter = { action: String, values: PropertyMap ->
                context.attributes.putAll(values)
                if (client.state == BotClient.State.Sleeping) {
                    println("{Signal '$action' values $values}")
                    client.doText(action)
                }
            }
            if (speechDevice is SignalProvider)
                providers.add(speechDevice)
            run()
        }

        println("{context = $context}")
        println("{inputAudioDevice = ${client.inputAudioDevice}}")
        println("{sttMode = ${client.sttMode}}")
        println("{device = ${params.device}}")
        if (listOf("rpi", "model1", "model2", "model3").contains(params.device)) {
            val gpio = GpioFactory.getInstance()
            light = if (params.device == "model2")
                Vk2ColorLed().apply {
                    set(Color.MAGENTA)
                }
            else
                BinLed(gpio).apply {
                    blink(500)
                }
            val button = gpio.provisionDigitalInputPin(RaspiPin.GPIO_04, PinPullResistance.PULL_DOWN)
            button.setShutdownOptions(true)
            button.addListener(GpioPinListenerDigital { event ->
                when (event.state) {
                    PinState.LOW -> {
                        light?.high()
                        client.touch()
                    }
                    PinState.HIGH -> {
                        light?.low()
                    }
                }
            })
        }
        client.open()
        if (params.input != "none") {
            InputStreamReader(
                if (params.input == "stdin")
                    System.`in`
                else
                    FileInputStream(params.input)
            ).use {
                val input = BufferedReader(it)
                while (true) {
                    val text = input.readLine()!!.trim()
                    client.outputQueue.clear()
                    when (text) {
                        "" -> {
                            println("[Click when ${client.state}]")
                            client.touch()
                        }
                        "exit", "quit" -> {
                            exitProcess(0)
                        }
                        else -> {
                            if (text.startsWith("audio:"))
                                client.socket.sendAudioData(File(text.substring(6)).readBytes())
                            else if (client.state == BotClient.State.Responding) {
                                responded = false
                                client.doText(text)
                            }
                        }
                    }
                    while (!responded && client.state != BotClient.State.Failed) {
                        Thread.sleep(50)
                    }
                }
            }
        }
    }
}