/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Playtika
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.playtika.test.common.utils;

import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.playtika.test.common.properties.CommonContainerProperties;
import com.playtika.test.common.properties.CommonContainerProperties.CopyFileProperties;
import com.playtika.test.common.properties.CommonContainerProperties.MountVolume;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.function.Consumer;

@Slf4j
@UtilityClass
public class ContainerUtils {

    public static final Duration DEFAULT_CONTAINER_WAIT_DURATION = Duration.ofSeconds(60);

    public static GenericContainer<?> configureCommonsAndStart(GenericContainer<?> container,
                                                               CommonContainerProperties properties,
                                                               Logger logger) {
        GenericContainer<?> updatedContainer = container
                .withStartupTimeout(properties.getTimeoutDuration())
                .withReuse(properties.isReuseContainer())
                .withLogConsumer(containerLogsConsumer(logger))
                .withImagePullPolicy(resolveImagePullPolicy(properties))
                .withEnv(properties.getEnv());

        for (CopyFileProperties fileToCopy : properties.getFilesToInclude()) {
            MountableFile mountableFile = MountableFile.forClasspathResource(fileToCopy.getClasspathResource());
            updatedContainer = updatedContainer.withCopyFileToContainer(mountableFile, fileToCopy.getContainerPath());
        }

        for (MountVolume mountVolume : properties.getMountVolumes()) {
            updatedContainer.addFileSystemBind(mountVolume.getHostPath(), mountVolume.getContainerPath(), mountVolume.getMode());
        }

        for (Capability capability : properties.getCapabilities()) {
            updatedContainer.withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withCapAdd(capability));
        }

        updatedContainer = properties.getCommand() != null ? updatedContainer.withCommand(properties.getCommand()) : updatedContainer;

        startAndLogTime(updatedContainer, logger);
        return updatedContainer;
    }

    private long startAndLogTime(GenericContainer<?> container, Logger logger) {
        Instant startTime = Instant.now();
        container.start();
        long startupTime = Duration.between(startTime, Instant.now()).toMillis() / 1000;

        String dockerImageName = container.getDockerImageName();
        String buildDate = getBuildDate(container, dockerImageName);
        // influxdb:1.4.3 build 2018-07-06T17:25:49+02:00 (2 years 11 months ago) startup time is 21 seconds
        if (startupTime < 10L) {
            logger.info("{} build {} startup time is {} seconds", dockerImageName, buildDate, startupTime);
        } else if (startupTime < 20L) {
            logger.warn("{} build {} startup time is {} seconds", dockerImageName, buildDate, startupTime);
        } else {
            logger.error("{} build {} startup time is {} seconds", dockerImageName, buildDate, startupTime);
        }
        return startupTime;
    }

    private String getBuildDate(GenericContainer<?> container, String dockerImageName) {
        String imageResponseCreated = null;
        try {
            InspectImageResponse inspectImageResponse = container.getDockerClient().inspectImageCmd(dockerImageName).exec();
            if(inspectImageResponse != null) {
                imageResponseCreated = inspectImageResponse.getCreated();
                return DateUtils.toDateAndTimeAgo(imageResponseCreated);
            } else {
                log.error("InspectImageResponse was null");
            }
        } catch (NotFoundException e) {
            log.error("Could not get InspectImageResponse", e);
        }
        return imageResponseCreated;
    }

    private static ImagePullPolicy resolveImagePullPolicy(CommonContainerProperties properties) {
        return properties.isUsePullAlwaysPolicy() ? PullPolicy.alwaysPull() : PullPolicy.defaultPolicy();
    }

    public static int getAvailableMappingPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot find available port for mapping: " + e.getMessage(), e);
        }
    }

    public static Consumer<OutputFrame> containerLogsConsumer(Logger log) {
        return (OutputFrame outputFrame) -> {
            switch (outputFrame.getType()) {
                case STDERR:
                    log.debug(outputFrame.getUtf8String());
                    break;
                case STDOUT:
                case END:
                    log.debug(outputFrame.getUtf8String());
                    break;
                default:
                    log.debug(outputFrame.getUtf8String());
                    break;
            }
        };
    }

    public static Container.ExecResult executeInContainer(ContainerState container, String... command) {
        try {
            return container.execInContainer(command);
        } catch (Exception e) {
            String format = String.format("Exception was thrown when executing: %s, for container: %s ", Arrays.toString(command), container.getContainerId());
            throw new IllegalStateException(format, e);
        }
    }

    public static Container.ExecResult executeAndCheckExitCode(ContainerState container, String... command) {
        try {
            Container.ExecResult execResult = container.execInContainer(command);
            log.debug("Executed command in container: {} with result: {}", container.getContainerId(), execResult);
            if (execResult.getExitCode() != 0) {
                throw new IllegalStateException("Failed to execute command. Execution result: " + execResult);
            }
            return execResult;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to execute command in container: " + container.getContainerId(), e);
        }
    }

}
