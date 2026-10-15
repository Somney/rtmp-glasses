/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// MockDeviceKitUiState - DAT MockDeviceKit Testing State
//
// These data classes manage the state of simulated wearable devices for DAT testing. MockDeviceKit
// provides a complete testing environment for DAT applications without requiring physical wearable
// devices.
//
// MockDeviceInfo encapsulates:
// - device: MockGlasses instance from DAT MockDeviceKit API
// - deviceId: UI identifier for tracking multiple mock devices
// - deviceName: Display name for the simulated device
// - hasCameraFeed: Whether mock video content has been configured
// - hasCapturedImage: Whether mock photo content has been configured
// - cameraSource: Which phone camera is being used as the source, if any

package com.meta.wearable.dat.externalsampleapps.cameraaccess.mockdevicekit

import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing

data class MockDeviceInfo(
    val device: MockGlasses,
    val deviceId: String,
    val deviceName: String,
    val hasCameraFeed: Boolean = false,
    val hasCapturedImage: Boolean = false,
    val cameraSource: CameraFacing? = null,
    val isPoweredOn: Boolean = false,
    val isDonned: Boolean = false,
    val isUnfolded: Boolean = false,
)

data class MockDeviceKitUiState(
    val isEnabled: Boolean = false,
    val pairedDevices: List<MockDeviceInfo> = emptyList(),
)
