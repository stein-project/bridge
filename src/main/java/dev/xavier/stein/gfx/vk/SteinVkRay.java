/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;

import dev.xavier.stein.gfx.SteinBuffer;
import dev.xavier.stein.gfx.SteinDevice;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * The acceleration structures of the Lens's ray tracing on Vulkan: one bottom-level structure per
 * terrain slice, built straight from the terrain buffer (its packed vertex, {@code
 * R16G16B16A16_UNORM}, with a transform that undoes the packing), and a top-level structure per
 * frame over them. The top level takes turns between three, so the one the GPU may still read for
 * the frames in flight is never rebuilt under it; a bottom level goes through the device's {@code
 * retire} when its slice is released.
 *
 * <p>Only with {@link SteinVkDevice#rayTracing}. Render thread. Pipeline: Stein GFX on Vulkan.
 */
public final class SteinVkRay {

    /** A bottom-level structure: its handle, the buffer that holds it, and its address. */
    public static final class Bottom {
        long handle;
        long buffer;
        SteinVkMemory.Allocation mem;
        long address;

        /** Its triangles (0 until built). */
        int triangles;
    }

    /** One build waiting for this frame's batch. */
    private static final class Pending {
        Bottom bottom;
        long vertexAddress;
        int vertices;
        long sizeScratch;
        long scratchAt;
    }

    private static final int TOPS = 3;

    /** Instances a top level holds at most (the slices near the camera). */
    public static final int MAX_INSTANCES = 8192;

    private static final int INSTANCE_BYTES = 64;

    private static SteinVkDevice device;
    private static long scratchAlignment = 256;

    // the transform that undoes the terrain's packing: local = q / 1024 - 16 = u * 65535 / 1024 -
    // 16
    private static long transformBuffer;
    private static SteinVkMemory.Allocation transformMem;
    private static long transformAddress;

    private static long scratchBuffer;
    private static SteinVkMemory.Allocation scratchMem;
    private static long scratchSize;
    private static long scratchAddress;

    private static final Pending[] PENDING = new Pending[256];
    private static int pending;

    // the top levels and their instance buffers (host visible, one per top)
    private static final long[] TOP = new long[TOPS];
    private static final long[] TOP_BUFFER = new long[TOPS];
    private static final SteinVkMemory.Allocation[] TOP_MEM = new SteinVkMemory.Allocation[TOPS];
    private static final long[] TOP_ADDRESS = new long[TOPS];
    private static final long[] INSTANCE_BUFFER = new long[TOPS];
    private static final SteinVkMemory.Allocation[] INSTANCE_MEM =
            new SteinVkMemory.Allocation[TOPS];
    private static final long[] INSTANCE_ADDRESS = new long[TOPS];
    private static long topScratchSize;
    private static int topTurn;
    private static int topInstances;

    private SteinVkRay() {}

    /** Whether ray tracing can be used: Stein GFX on Vulkan with the device's ray tracing. */
    public static boolean available() {
        return SteinDevice.get() instanceof SteinVkDevice d && d.rayTracing;
    }

    private static void init() {
        if (device != null) {
            return;
        }
        device = (SteinVkDevice) SteinDevice.get();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceAccelerationStructurePropertiesKHR as =
                    VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack)
                            .sType$Default();
            VkPhysicalDeviceProperties2 p2 =
                    VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(as.address());
            org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2(device.physical(), p2);
            scratchAlignment = Math.max(1, as.minAccelerationStructureScratchOffsetAlignment());
        }
        SteinVkMemory.Allocation[] a = new SteinVkMemory.Allocation[1];
        transformBuffer =
                device.createRawBuffer(
                        48,
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                                | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        0,
                        "ray: terrain transform",
                        a);
        transformMem = a[0];
        float s = 65535f / 1024f;
        float[] m = {s, 0, 0, -16f, 0, s, 0, -16f, 0, 0, s, -16f};
        for (int i = 0; i < 12; i++) {
            MemoryUtil.memPutFloat(transformMem.mapped + i * 4L, m[i]);
        }
        transformAddress = address(transformBuffer);
        for (int i = 0; i < PENDING.length; i++) {
            PENDING[i] = new Pending();
        }
    }

    private static long address(long buffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return vkGetBufferDeviceAddress(
                    device.vk(),
                    VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
        }
    }

    /** The address of a buffer of ours (vertex, index or storage, with ray tracing on). */
    public static long address(SteinBuffer buffer) {
        init();
        return address(buffer.handle());
    }

    /**
     * Asks for a bottom level over {@code vertices} packed terrain vertices (quads, as the shared
     * index buffer draws them) at {@code vertexAddress}; built at the next {@link #buildPending}.
     * Returns false when this frame's batch is full (ask again next frame).
     */
    public static boolean requestBottom(Bottom b, long vertexAddress, int vertices) {
        init();
        if (pending == PENDING.length || vertices < 4) {
            return false;
        }
        Pending p = PENDING[pending++];
        p.bottom = b;
        p.vertexAddress = vertexAddress;
        p.vertices = vertices;
        return true;
    }

    /** How many builds wait for this frame. */
    public static int pendingCount() {
        return pending;
    }

    /**
     * Builds the waiting bottom levels in one batch, reading the indices at {@code indexAddress}
     * (the terrain's shared quad indices). Outside any pass: suspends the open one.
     */
    public static void buildPending(long indexAddress) {
        if (pending == 0) {
            return;
        }
        init();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geoms =
                    VkAccelerationStructureGeometryKHR.calloc(pending, stack);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer infos =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(pending, stack);
            VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(pending, stack);
            PointerBuffer rangePtrs = stack.mallocPointer(pending);
            VkAccelerationStructureBuildSizesInfoKHR sizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            IntBuffer count = stack.mallocInt(1);
            long scratchTotal = 0;
            for (int i = 0; i < pending; i++) {
                Pending p = PENDING[i];
                int triangles = p.vertices / 4 * 2;
                VkAccelerationStructureGeometryKHR g = geoms.get(i);
                g.sType$Default()
                        .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                        .flags(0); // not opaque: the sun's rays look at each hit (clear texels,
                // small plants)
                g.geometry()
                        .triangles()
                        .sType$Default()
                        .vertexFormat(VK_FORMAT_R16G16B16A16_UNORM)
                        .vertexStride(16)
                        .maxVertex(p.vertices - 1)
                        .indexType(VK_INDEX_TYPE_UINT32);
                g.geometry().triangles().vertexData().deviceAddress(p.vertexAddress);
                g.geometry().triangles().indexData().deviceAddress(indexAddress);
                g.geometry().triangles().transformData().deviceAddress(transformAddress);
                VkAccelerationStructureBuildGeometryInfoKHR info = infos.get(i);
                info.sType$Default()
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                        .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                        .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                        .geometryCount(
                                1) // (pGeometries leaves the count alone: ppGeometries is the other
                        // way)
                        .pGeometries(VkAccelerationStructureGeometryKHR.create(g.address(), 1));
                count.put(0, triangles);
                vkGetAccelerationStructureBuildSizesKHR(
                        device.vk(),
                        VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                        info,
                        count,
                        sizes);
                Bottom b = p.bottom;
                create(b, sizes.accelerationStructureSize(), stack);
                b.triangles = triangles;
                info.dstAccelerationStructure(b.handle);
                p.scratchAt = scratchTotal;
                scratchTotal += align(sizes.buildScratchSize(), scratchAlignment);
                ranges.get(i).primitiveCount(triangles).primitiveOffset(0).firstVertex(0);
                rangePtrs.put(i, ranges.get(i).address());
            }
            ensureScratch(scratchTotal);
            for (int i = 0; i < pending; i++) {
                infos.get(i).scratchData().deviceAddress(scratchAddress + PENDING[i].scratchAt);
            }
            VkCommandBuffer c = device.outside();
            // what was uploaded or copied into the terrain buffer, before the builds read it
            // (and the last builds, whose scratch this batch uses again)
            barrier(
                    c,
                    VK_PIPELINE_STAGE_TRANSFER_BIT
                            | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT
                            | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                            | VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_ACCESS_TRANSFER_WRITE_BIT
                            | VK_ACCESS_SHADER_WRITE_BIT
                            | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            vkCmdBuildAccelerationStructuresKHR(c, infos, rangePtrs);
            // the bottoms before the top level that points at them
            barrier(
                    c,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                            | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
        }
        for (int i = 0; i < pending; i++) {
            PENDING[i].bottom = null;
        }
        pending = 0;
    }

    private static void create(Bottom b, long size, MemoryStack stack) {
        SteinVkMemory.Allocation[] a = new SteinVkMemory.Allocation[1];
        b.buffer =
                device.createRawBuffer(
                        size,
                        VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                                | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                        0,
                        "ray: terrain slice",
                        a);
        b.mem = a[0];
        LongBuffer lp = stack.mallocLong(1);
        SteinVkDevice.check(
                vkCreateAccelerationStructureKHR(
                        device.vk(),
                        VkAccelerationStructureCreateInfoKHR.calloc(stack)
                                .sType$Default()
                                .buffer(b.buffer)
                                .size(size)
                                .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR),
                        null,
                        lp),
                "bottom-level acceleration structure");
        b.handle = lp.get(0);
        b.address =
                vkGetAccelerationStructureDeviceAddressKHR(
                        device.vk(),
                        VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                                .sType$Default()
                                .accelerationStructure(b.handle));
    }

    /** Releases a bottom level once the GPU is done with the frames that may read it. */
    public static void release(Bottom b) {
        if (b.handle == VK_NULL_HANDLE) {
            return;
        }
        long h = b.handle;
        long buf = b.buffer;
        SteinVkMemory.Allocation m = b.mem;
        b.handle = VK_NULL_HANDLE;
        b.buffer = VK_NULL_HANDLE;
        b.mem = null;
        b.address = 0L;
        b.triangles = 0;
        SteinVkDevice d = device;
        d.retire(
                () -> {
                    vkDestroyAccelerationStructureKHR(d.vk(), h, null);
                    vkDestroyBuffer(d.vk(), buf, null);
                    d.free(m);
                });
    }

    /** Whether the bottom level is built (and can be put in a top level). */
    public static boolean built(Bottom b) {
        return b.handle != VK_NULL_HANDLE && b.triangles > 0;
    }

    // ---- the top level ----

    /**
     * Starts this frame's top level: the next of the three; returns false when ray tracing is off.
     */
    public static boolean beginTop() {
        if (!available()) {
            return false;
        }
        init();
        topTurn = (topTurn + 1) % TOPS;
        topInstances = 0;
        if (TOP[topTurn] == VK_NULL_HANDLE) {
            createTop(topTurn);
        }
        return true;
    }

    /**
     * Adds an instance of {@code b} moved by {@code (tx, ty, tz)} (the slice's origin, relative to
     * the camera), with {@code custom} for the shaders (24 bits: the slice's first vertex in the
     * terrain buffer).
     */
    public static boolean addInstance(Bottom b, float tx, float ty, float tz, int custom) {
        if (topInstances == MAX_INSTANCES || !built(b)) {
            return false;
        }
        long p = INSTANCE_MEM[topTurn].mapped + (long) topInstances * INSTANCE_BYTES;
        // a 3x4 row-major transform: identity rotation, the translation
        MemoryUtil.memPutFloat(p, 1f);
        MemoryUtil.memPutFloat(p + 4, 0f);
        MemoryUtil.memPutFloat(p + 8, 0f);
        MemoryUtil.memPutFloat(p + 12, tx);
        MemoryUtil.memPutFloat(p + 16, 0f);
        MemoryUtil.memPutFloat(p + 20, 1f);
        MemoryUtil.memPutFloat(p + 24, 0f);
        MemoryUtil.memPutFloat(p + 28, ty);
        MemoryUtil.memPutFloat(p + 32, 0f);
        MemoryUtil.memPutFloat(p + 36, 0f);
        MemoryUtil.memPutFloat(p + 40, 1f);
        MemoryUtil.memPutFloat(p + 44, tz);
        MemoryUtil.memPutInt(p + 48, (custom & 0xFFFFFF) | (0xFF << 24));
        MemoryUtil.memPutInt(
                p + 52, VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR << 24);
        MemoryUtil.memPutLong(p + 56, b.address);
        topInstances++;
        return true;
    }

    /** Builds this frame's top level over the instances added; outside any pass. */
    public static void endTop() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer g =
                    VkAccelerationStructureGeometryKHR.calloc(1, stack);
            g.get(0)
                    .sType$Default()
                    .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                    .flags(0); // the sun's rays look at each hit
            g.get(0).geometry().instances().sType$Default().arrayOfPointers(false);
            g.get(0).geometry().instances().data().deviceAddress(INSTANCE_ADDRESS[topTurn]);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            info.get(0)
                    .sType$Default()
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .dstAccelerationStructure(TOP[topTurn])
                    .geometryCount(1)
                    .pGeometries(g);
            ensureScratch(topScratchSize);
            info.get(0).scratchData().deviceAddress(scratchAddress);
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0).primitiveCount(topInstances);
            PointerBuffer pr = stack.pointers(range.get(0).address());
            VkCommandBuffer c = device.outside();
            barrier(
                    c,
                    VK_PIPELINE_STAGE_HOST_BIT
                            | VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_ACCESS_HOST_WRITE_BIT | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            vkCmdBuildAccelerationStructuresKHR(c, info, pr);
            // the top level before the shaders that trace it this frame
            barrier(
                    c,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        }
    }

    /** This frame's top level (its handle), for the shaders' descriptor. */
    public static long top() {
        return TOP[topTurn];
    }

    /** The instances in this frame's top level. */
    public static int topInstances() {
        return topInstances;
    }

    private static void createTop(int i) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SteinVkMemory.Allocation[] a = new SteinVkMemory.Allocation[1];
            INSTANCE_BUFFER[i] =
                    device.createRawBuffer(
                            (long) MAX_INSTANCES * INSTANCE_BYTES,
                            VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                                    | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                    | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                            0,
                            "ray: instances",
                            a);
            INSTANCE_MEM[i] = a[0];
            INSTANCE_ADDRESS[i] = address(INSTANCE_BUFFER[i]);
            VkAccelerationStructureGeometryKHR.Buffer g =
                    VkAccelerationStructureGeometryKHR.calloc(1, stack);
            g.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
            g.get(0).geometry().instances().sType$Default().arrayOfPointers(false);
            VkAccelerationStructureBuildGeometryInfoKHR info =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                            .sType$Default()
                            .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                            .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                            .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                            .geometryCount(1)
                            .pGeometries(g);
            VkAccelerationStructureBuildSizesInfoKHR sizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(
                    device.vk(),
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    info,
                    stack.ints(MAX_INSTANCES),
                    sizes);
            topScratchSize = Math.max(topScratchSize, sizes.buildScratchSize());
            TOP_BUFFER[i] =
                    device.createRawBuffer(
                            sizes.accelerationStructureSize(),
                            VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                                    | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                            0,
                            "ray: top level",
                            a);
            TOP_MEM[i] = a[0];
            LongBuffer lp = stack.mallocLong(1);
            SteinVkDevice.check(
                    vkCreateAccelerationStructureKHR(
                            device.vk(),
                            VkAccelerationStructureCreateInfoKHR.calloc(stack)
                                    .sType$Default()
                                    .buffer(TOP_BUFFER[i])
                                    .size(sizes.accelerationStructureSize())
                                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR),
                            null,
                            lp),
                    "top-level acceleration structure");
            TOP[i] = lp.get(0);
            TOP_ADDRESS[i] =
                    vkGetAccelerationStructureDeviceAddressKHR(
                            device.vk(),
                            VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                                    .sType$Default()
                                    .accelerationStructure(TOP[i]));
        }
    }

    // ---- shared ----

    /**
     * The scratch, grown when a build needs more (the old one retired: a build already recorded may
     * still use it).
     */
    private static void ensureScratch(long size) {
        if (size <= scratchSize) {
            return;
        }
        if (scratchBuffer != VK_NULL_HANDLE) {
            long b = scratchBuffer;
            SteinVkMemory.Allocation m = scratchMem;
            SteinVkDevice d = device;
            d.retire(
                    () -> {
                        vkDestroyBuffer(d.vk(), b, null);
                        d.free(m);
                    });
        }
        long grown = Math.max(size, scratchSize * 2);
        grown = Math.max(grown, 1L << 20);
        SteinVkMemory.Allocation[] a = new SteinVkMemory.Allocation[1];
        scratchBuffer =
                device.createRawBuffer(
                        grown + scratchAlignment,
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                        0,
                        "ray: scratch",
                        a);
        scratchMem = a[0];
        scratchSize = grown;
        scratchAddress = align(address(scratchBuffer), scratchAlignment);
    }

    private static long align(long v, long a) {
        return (v + a - 1) / a * a;
    }

    private static void barrier(
            VkCommandBuffer c, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b =
                    VkMemoryBarrier.calloc(1, stack)
                            .sType$Default()
                            .srcAccessMask(srcAccess)
                            .dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(c, srcStage, dstStage, 0, b, null, null);
        }
    }
}
