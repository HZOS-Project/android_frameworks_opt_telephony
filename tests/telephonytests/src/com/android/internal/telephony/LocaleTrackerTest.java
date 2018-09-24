/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.AsyncResult;
import android.os.HandlerThread;
import android.os.Message;
import android.telephony.CellIdentityGsm;
import android.telephony.CellInfoGsm;
import android.telephony.ServiceState;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

public class LocaleTrackerTest extends TelephonyTest {

    private static final String US_MCC = "310";
    private static final String FAKE_MNC = "123";
    private static final String US_COUNTRY_CODE = "us";
    private static final String COUNTRY_CODE_UNAVAILABLE = "";

    private LocaleTracker mLocaleTracker;

    private CellInfoGsm mCellInfo;
    private WifiManager mWifiManager;

    private class LocaleTrackerTestHandler extends HandlerThread {

        private LocaleTrackerTestHandler(String name) {
            super(name);
        }

        @Override
        public void onLooperPrepared() {
            mLocaleTracker = new LocaleTracker(mPhone, this.getLooper());
            setReady(true);
        }
    }

    private LocaleTrackerTestHandler mHandlerThread;

    @Before
    public void setUp() throws Exception {
        logd("LocaleTrackerTest +Setup!");
        super.setUp(getClass().getSimpleName());

        mHandlerThread = new LocaleTrackerTestHandler("LocaleTrackerTestHandler");
        mHandlerThread.start();
        waitUntilReady();


        // This is a workaround to bypass setting system properties, which causes access violation.
        doReturn(-1).when(mPhone).getPhoneId();
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);

        mCellInfo = new CellInfoGsm();
        mCellInfo.setCellIdentity(new CellIdentityGsm(Integer.parseInt(US_MCC),
                Integer.parseInt(FAKE_MNC), 0, 0));
        doAnswer(invocation -> {
            Message m = invocation.getArgument(1);
            AsyncResult.forMessage(m, Arrays.asList(mCellInfo), null);
            m.sendToTarget();
            return null; }).when(mPhone).getAllCellInfo(any(), any());

        logd("LocaleTrackerTest -Setup!");
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
        mHandlerThread.join();
        super.tearDown();
    }

    private void sendServiceState(int state) {
        ServiceState ss = new ServiceState();
        ss.setState(state);
        AsyncResult ar = new AsyncResult(null, ss, null);
        mLocaleTracker.sendMessage(
                mLocaleTracker.obtainMessage(2 /*SERVICE_STATE_CHANGED*/, ar));
        waitForHandlerAction(mLocaleTracker, 100);
    }

    private void sendGsmCellInfo() {
        // send an unsol cell info
        mLocaleTracker
                .obtainMessage(4 /*UNSOL_CELL_INFO*/,
                        new AsyncResult(null, Arrays.asList(mCellInfo), null))
                .sendToTarget();
        waitForHandlerAction(mLocaleTracker, 100);
    }

    @Test
    @SmallTest
    public void testUpdateOperatorNumericSync() throws Exception {
        mLocaleTracker.updateOperatorNumeric(US_MCC + FAKE_MNC);
        assertEquals(US_COUNTRY_CODE, mLocaleTracker.getCurrentCountry());
        verify(mWifiManager).setCountryCode(US_COUNTRY_CODE);
    }

    @Test
    @SmallTest
    public void testNoSim() throws Exception {
        mLocaleTracker.updateOperatorNumeric("");
        sendGsmCellInfo();
        sendServiceState(ServiceState.STATE_EMERGENCY_ONLY);
        assertEquals(US_COUNTRY_CODE, mLocaleTracker.getCurrentCountry());
        verify(mWifiManager).setCountryCode(US_COUNTRY_CODE);
        assertTrue(mLocaleTracker.isTracking());
    }

    @Test
    @SmallTest
    public void testBootupInAirplaneModeOn() throws Exception {
        mLocaleTracker.updateOperatorNumeric("");
        sendServiceState(ServiceState.STATE_POWER_OFF);
        assertEquals(COUNTRY_CODE_UNAVAILABLE, mLocaleTracker.getCurrentCountry());
        verify(mWifiManager).setCountryCode(COUNTRY_CODE_UNAVAILABLE);
        assertFalse(mLocaleTracker.isTracking());
    }

    @Test
    @SmallTest
    public void testToggleAirplaneModeOn() throws Exception {
        sendServiceState(ServiceState.STATE_IN_SERVICE);
        mLocaleTracker.updateOperatorNumeric(US_MCC + FAKE_MNC);
        assertEquals(US_COUNTRY_CODE, mLocaleTracker.getCurrentCountry());
        verify(mWifiManager).setCountryCode(US_COUNTRY_CODE);
        assertFalse(mLocaleTracker.isTracking());

        mLocaleTracker.updateOperatorNumeric("");
        waitForHandlerAction(mLocaleTracker, 100);
        assertEquals(COUNTRY_CODE_UNAVAILABLE, mLocaleTracker.getCurrentCountry());
        verify(mWifiManager, times(2)).setCountryCode(COUNTRY_CODE_UNAVAILABLE);
        sendServiceState(ServiceState.STATE_POWER_OFF);
        assertFalse(mLocaleTracker.isTracking());
    }

    @Test
    @SmallTest
    public void testToggleAirplaneModeOff() throws Exception {
        sendServiceState(ServiceState.STATE_POWER_OFF);
        mLocaleTracker.updateOperatorNumeric("");
        waitForHandlerAction(mLocaleTracker, 100);
        assertEquals(COUNTRY_CODE_UNAVAILABLE, mLocaleTracker.getCurrentCountry());
        verify(mWifiManager).setCountryCode(COUNTRY_CODE_UNAVAILABLE);
        assertFalse(mLocaleTracker.isTracking());

        sendServiceState(ServiceState.STATE_OUT_OF_SERVICE);
        waitForHandlerAction(mLocaleTracker, 100);
        assertTrue(mLocaleTracker.isTracking());
        waitForHandlerAction(mLocaleTracker, 100);
        assertEquals(US_COUNTRY_CODE, mLocaleTracker.getCurrentCountry());
    }
}
