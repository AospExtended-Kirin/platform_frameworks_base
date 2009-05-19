/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.telephony.cdma;

import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Checkin;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Telephony.Intents;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.cdma.CdmaCellLocation;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.TimeUtils;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.DataConnectionTracker;
// pretty sure importing stuff from GSM is bad:
import com.android.internal.telephony.gsm.MccTable;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.TelephonyEventLog;
import com.android.internal.telephony.TelephonyIntents;

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_OPERATOR_ALPHA;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_OPERATOR_ISMANUAL;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_OPERATOR_ISROAMING;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_OPERATOR_NUMERIC;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA;

import java.util.Arrays;
import java.util.Date;
import java.util.TimeZone;

/**
 * {@hide}
 */
final class CdmaServiceStateTracker extends ServiceStateTracker {
    //***** Instance Variables
    CDMAPhone phone;
    CdmaCellLocation cellLoc;
    CdmaCellLocation newCellLoc;

    /**
     * TODO(Teleca): I don't think the initialization to -1 for all of these are
     * really necessary, I don't seem them in GsmServiceStateTracker. Also,
     * all of the other initialization is unnecessary as I believe Java guarantees
     * 0, false & null, but if you think it's better than do all of them there are
     * a few that aren't initialized.
     */

    /**
     *  The access technology currently in use: DATA_ACCESS_
     */
    private int networkType = 0;
    private int newNetworkType = 0;

    private boolean mCdmaRoaming = false;
    private int mRoamingIndicator = -1;
    private int mIsInPrl = -1;
    private int mDefaultRoamingIndicator = -1;

    /**
     * TODO(Teleca): Maybe these should be initialized to STATE_OUT_OF_SERVICE like gprsState
     * in GsmServiceStateTracker and remove the comment.
     */
    private int cdmaDataConnectionState = -1; // Initially we assume no data connection
    private int newCdmaDataConnectionState = -1; // Initially we assume no data connection
    private int mRegistrationState = -1;
    private RegistrantList cdmaDataConnectionAttachedRegistrants = new RegistrantList();
    private RegistrantList cdmaDataConnectionDetachedRegistrants = new RegistrantList();

    private boolean mGotCountryCode = false;

    // We can't register for SIM_RECORDS_LOADED immediately because the
    // SIMRecords object may not be instantiated yet.
    private boolean mNeedToRegForRuimLoaded;

    // Keep track of SPN display rules, so we only broadcast intent if something changes.
    private String curSpn = null;
    private String curEriText = null;
    private int curSpnRule = 0;

    private String mMdn = null;
    private int mHomeSystemId = -1;
    private int mHomeNetworkId = -1;
    private String mMin = null;
    private boolean isEriTextLoaded = false;
    private boolean isSubscriptionFromRuim = false;

    /**
     * TODO(Teleca): Is this purely for debugging purposes, or do we expect this string to be
     * passed around (eg, to the UI)? If the latter, it would be better to pass around a
     * reasonCode, and let the UI provide its own strings.
     */
    private String mRegistrationDeniedReason = null;

    //***** Constants
    static final String LOG_TAG = "CDMA";

    private ContentResolver cr;

    private ContentObserver mAutoTimeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            Log.i("CdmaServiceStateTracker", "Auto time state called ...");
            //NOTE in CDMA NITZ is not used
        }
    };


    //***** Constructors

    public CdmaServiceStateTracker(CDMAPhone phone) {
        super();

        this.phone = phone;
        cm = phone.mCM;
        ss = new ServiceState();
        newSS = new ServiceState();
        cellLoc = new CdmaCellLocation();
        newCellLoc = new CdmaCellLocation();
        mSignalStrength = new SignalStrength();

        cm.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);
        cm.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);

        cm.registerForNetworkStateChanged(this, EVENT_NETWORK_STATE_CHANGED_CDMA, null);
        cm.setOnSignalStrengthUpdate(this, EVENT_SIGNAL_STRENGTH_UPDATE, null);

        cm.registerForRUIMReady(this, EVENT_RUIM_READY, null);

        cm.registerForNVReady(this, EVENT_NV_READY, null);
        phone.registerForEriFileLoaded(this, EVENT_ERI_FILE_LOADED, null);

        // system setting property AIRPLANE_MODE_ON is set in Settings.
        int airplaneMode = Settings.System.getInt(
                phone.getContext().getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0);
        mDesiredPowerState = ! (airplaneMode > 0);

        cr = phone.getContext().getContentResolver();
        cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.AUTO_TIME), true,
                mAutoTimeObserver);
        setSignalStrengthDefaultValues();

        mNeedToRegForRuimLoaded = true;
    }

    public void dispose() {
        //Unregister for all events
        cm.unregisterForAvailable(this);
        cm.unregisterForRadioStateChanged(this);
        cm.unregisterForNetworkStateChanged(this);
        cm.unregisterForRUIMReady(this);
        cm.unregisterForNVReady(this);
        phone.unregisterForEriFileLoaded(this);
        phone.mRuimRecords.unregisterForRecordsLoaded(this);
        cm.unSetOnSignalStrengthUpdate(this);
        cr.unregisterContentObserver(this.mAutoTimeObserver);
    }

    protected void finalize() {
        if (DBG) log("CdmaServiceStateTracker finalized");
    }

    void registerForNetworkAttach(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        networkAttachedRegistrants.add(r);

        if (ss.getState() == ServiceState.STATE_IN_SERVICE) {
            r.notifyRegistrant();
        }
    }

    void unregisterForNetworkAttach(Handler h) {
        networkAttachedRegistrants.remove(h);
    }

    /**
     * Registration point for transition into Data attached.
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    /*protected*/ void
    registerForCdmaDataConnectionAttached(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        cdmaDataConnectionAttachedRegistrants.add(r);

        if (cdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_1xRTT
           || cdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_EVDO_0
           || cdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_EVDO_A) {
            r.notifyRegistrant();
        }
    }
    void unregisterForCdmaDataConnectionAttached(Handler h) {
        cdmaDataConnectionAttachedRegistrants.remove(h);
    }

    /**
     * Registration point for transition into Data detached.
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    /*protected*/  void
    registerForCdmaDataConnectionDetached(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        cdmaDataConnectionDetachedRegistrants.add(r);

        if (cdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_1xRTT
           && cdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_EVDO_0
           && cdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_EVDO_A) {
            r.notifyRegistrant();
        }
    }
    void unregisterForCdmaDataConnectionDetached(Handler h) {
        cdmaDataConnectionDetachedRegistrants.remove(h);
    }

    //***** Called from CDMAPhone
    public void
    getLacAndCid(Message onComplete) {
        cm.getRegistrationState(obtainMessage(
                EVENT_GET_LOC_DONE_CDMA, onComplete));
    }


    //***** Overridden from ServiceStateTracker
    public void
    handleMessage (Message msg) {
        AsyncResult ar;
        int[] ints;
        String[] strings;

        switch (msg.what) {
        case EVENT_RADIO_AVAILABLE:
            //this is unnecessary
            //setPowerStateToDesired();
            break;

        case EVENT_RUIM_READY:
            // The RUIM is now ready i.e if it was locked
            // it has been unlocked. At this stage, the radio is already
            // powered on.
            isSubscriptionFromRuim = true;
            if (mNeedToRegForRuimLoaded) {
                phone.mRuimRecords.registerForRecordsLoaded(this,
                        EVENT_RUIM_RECORDS_LOADED, null);
                mNeedToRegForRuimLoaded = false;
            }
            // restore the previous network selection.
            pollState();

            // Signal strength polling stops when radio is off
            queueNextSignalStrengthPoll();
            break;

        case EVENT_NV_READY:
            isSubscriptionFromRuim = false;
            pollState();
            // Signal strength polling stops when radio is off
            queueNextSignalStrengthPoll();
            break;

        case EVENT_RADIO_STATE_CHANGED:
            // This will do nothing in the radio not
            // available case
            setPowerStateToDesired();
            pollState();
            break;

        case EVENT_NETWORK_STATE_CHANGED_CDMA:
            pollState();
            break;

        case EVENT_GET_SIGNAL_STRENGTH:
            // This callback is called when signal strength is polled
            // all by itself

            if (!(cm.getRadioState().isOn()) || (cm.getRadioState().isGsm())) {
                // Polling will continue when radio turns back on
                return;
            }
            ar = (AsyncResult) msg.obj;
            onSignalStrengthResult(ar);
            queueNextSignalStrengthPoll();

            break;

        case EVENT_GET_LOC_DONE_CDMA:
            ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                String states[] = (String[])ar.result;
                int baseStationId = -1;
                int baseStationLongitude = -1;
                int baseStationLatitude = -1;

                int baseStationData[] = {
                        -1, // baseStationId
                        -1, // baseStationLatitude
                        -1  // baseStationLongitude
                };

                if (states.length == 3) {
                    for(int i = 0; i < states.length; i++) {
                        try {
                            if (states[i] != null && states[i].length() > 0) {
                                baseStationData[i] = Integer.parseInt(states[i], 16);
                            }
                        } catch (NumberFormatException ex) {
                            Log.w(LOG_TAG, "error parsing cell location data: " + ex);
                        }
                    }
                }

                // only update if cell location really changed
                if (cellLoc.getBaseStationId() != baseStationData[0]
                        || cellLoc.getBaseStationLatitude() != baseStationData[1]
                        || cellLoc.getBaseStationLongitude() != baseStationData[2]) {
                    cellLoc.setCellLocationData(baseStationData[0],
                                                baseStationData[1],
                                                baseStationData[2]);
                   phone.notifyLocationChanged();
                }
            }

            if (ar.userObj != null) {
                AsyncResult.forMessage(((Message) ar.userObj)).exception
                = ar.exception;
                ((Message) ar.userObj).sendToTarget();
            }
            break;

        case EVENT_POLL_STATE_REGISTRATION_CDMA:
        case EVENT_POLL_STATE_OPERATOR_CDMA:
        case EVENT_POLL_STATE_CDMA_SUBSCRIPTION:
            ar = (AsyncResult) msg.obj;
            handlePollStateResult(msg.what, ar);
            break;

        case EVENT_POLL_SIGNAL_STRENGTH:
            // Just poll signal strength...not part of pollState()

            cm.getSignalStrength(obtainMessage(EVENT_GET_SIGNAL_STRENGTH));
            break;

        case EVENT_SIGNAL_STRENGTH_UPDATE:
            // This is a notification from
            // CommandsInterface.setOnSignalStrengthUpdate

            ar = (AsyncResult) msg.obj;

            // The radio is telling us about signal strength changes
            // we don't have to ask it
            dontPollSignalStrength = true;

            onSignalStrengthResult(ar);
            break;

        case EVENT_RUIM_RECORDS_LOADED:
            updateSpnDisplay();
            break;

        case EVENT_LOCATION_UPDATES_ENABLED:
            ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                getLacAndCid(null);
            }
            break;

        case EVENT_ERI_FILE_LOADED:
            // Repoll the state once the ERI file has been loaded
            if (DBG) log("[CdmaServiceStateTracker] ERI file has been loaded, repolling.");
            pollState();
            break;

        default:
            Log.e(LOG_TAG, "Unhandled message with number: " + msg.what);
        break;
        }
    }

    //***** Private Instance Methods

    protected void setPowerStateToDesired()
    {
        // If we want it on and it's off, turn it on
        if (mDesiredPowerState
            && cm.getRadioState() == CommandsInterface.RadioState.RADIO_OFF) {
            cm.setRadioPower(true, null);
        } else if (!mDesiredPowerState && cm.getRadioState().isOn()) {
            DataConnectionTracker dcTracker = phone.mDataConnection;
            if (! dcTracker.isDataConnectionAsDesired()) {

                EventLog.List val = new EventLog.List(
                        dcTracker.getStateInString(),
                        (dcTracker.getAnyDataEnabled() ? 1 : 0) );
                EventLog.writeEvent(TelephonyEventLog.EVENT_LOG_DATA_STATE_RADIO_OFF, val);
            }
            dcTracker.cleanConnectionBeforeRadioOff();

            // Poll data state up to 15 times, with a 100ms delay
            // totaling 1.5 sec. Normal data disable action will finish in 100ms.
            for (int i = 0; i < MAX_NUM_DATA_STATE_READS; i++) {
                DataConnectionTracker.State currentState = dcTracker.getState();
                if (currentState != DataConnectionTracker.State.CONNECTED
                        && currentState != DataConnectionTracker.State.DISCONNECTING) {
                    if (DBG) log("Data shutdown complete.");
                    break;
                }
                SystemClock.sleep(DATA_STATE_POLL_SLEEP_MS);
            }
            // If it's on and available and we want it off..
            cm.setRadioPower(false, null);
        } // Otherwise, we're in the desired state
    }

    protected void updateSpnDisplay() {

        // TODO(Teleca): Check this method again, because it is not sure at the moment how
        // the RUIM handles the SIM stuff. Please complete this function.

        //int rule = phone.mRuimRecords.getDisplayRule(ss.getOperatorNumeric());
        String spn = null; //phone.mRuimRecords.getServiceProviderName();
        String eri = ss.getOperatorAlphaLong();

        if (!TextUtils.equals(this.curEriText, eri)) {
            //TODO  (rule & SIMRecords.SPN_RULE_SHOW_SPN) == SIMRecords.SPN_RULE_SHOW_SPN;
            boolean showSpn = false;
            //TODO  (rule & SIMRecords.SPN_RULE_SHOW_PLMN) == SIMRecords.SPN_RULE_SHOW_PLMN;
            boolean showEri = true;
            Intent intent = new Intent(Intents.SPN_STRINGS_UPDATED_ACTION);
            intent.putExtra(Intents.EXTRA_SHOW_SPN, showSpn);
            intent.putExtra(Intents.EXTRA_SPN, spn);
            intent.putExtra(Intents.EXTRA_SHOW_PLMN, showEri);
            intent.putExtra(Intents.EXTRA_PLMN, eri);
            phone.getContext().sendStickyBroadcast(intent);
        }

        //curSpnRule = rule;
        //curSpn = spn;
        this.curEriText = eri;
    }

    /**
     * Handle the result of one of the pollState()-related requests
     */

    protected void
    handlePollStateResult (int what, AsyncResult ar) {
        int ints[];
        String states[];

        // Ignore stale requests from last poll
        if (ar.userObj != pollingContext) return;

        if (ar.exception != null) {
            CommandException.Error err=null;

            if (ar.exception instanceof CommandException) {
                err = ((CommandException)(ar.exception)).getCommandError();
            }

            if (err == CommandException.Error.RADIO_NOT_AVAILABLE) {
                // Radio has crashed or turned off
                cancelPollState();
                return;
            }

            if (!cm.getRadioState().isOn()) {
                // Radio has crashed or turned off
                cancelPollState();
                return;
            }

            if (err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW &&
                    err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW) {
                Log.e(LOG_TAG,
                        "RIL implementation has returned an error where it must succeed",
                        ar.exception);
            }
        } else try {
            switch (what) {
            case EVENT_POLL_STATE_REGISTRATION_CDMA: // Handle RIL_REQUEST_REGISTRATION_STATE,
                                                     // the offset is because we don't want the
                                                     // first 3 values in the
                                                     // responseValuesRegistrationState array.
                final int offset = 3;
                states = (String[])ar.result;

                /**
                 * TODO(Teleca): Change from array to a "Class" or local
                 * variables so names instead of index's can be used.
                 */
                int responseValuesRegistrationState[] = {
                        -1, //[0] radioTechnology
                        -1, //[1] baseStationId
                        -1, //[2] baseStationLatitude
                        -1, //[3] baseStationLongitude
                         0, //[4] cssIndicator; init with 0, because it is treated as a boolean
                        -1, //[5] systemId
                        -1, //[6] networkId
                        -1, //[7] Roaming indicator
                        -1, //[8] Indicates if current system is in PRL
                        -1, //[9] Is default roaming indicator from PRL
                        -1, //[10] If registration state is 3 this is reason for denial
                };

                if (states.length == 14) {
                    try {
                        this.mRegistrationState = Integer.parseInt(states[0]);
                    } catch (NumberFormatException ex) {
                        Log.w(LOG_TAG, "error parsing RegistrationState: " + ex);
                    }
                    try {
                        responseValuesRegistrationState[0] = Integer.parseInt(states[3]);
                        responseValuesRegistrationState[1] = Integer.parseInt(states[4], 16);
                        responseValuesRegistrationState[2] = Integer.parseInt(states[5], 16);
                        responseValuesRegistrationState[3] = Integer.parseInt(states[6], 16);
                        responseValuesRegistrationState[4] = Integer.parseInt(states[7]);
                        responseValuesRegistrationState[5] = Integer.parseInt(states[8]);
                        responseValuesRegistrationState[6] = Integer.parseInt(states[9]);
                        responseValuesRegistrationState[7] = Integer.parseInt(states[10]);
                        responseValuesRegistrationState[8] = Integer.parseInt(states[11]);
                        responseValuesRegistrationState[9] = Integer.parseInt(states[12]);
                        responseValuesRegistrationState[10] = Integer.parseInt(states[13]);
                    }
                    catch(NumberFormatException ex) {
                        Log.w(LOG_TAG, "Warning! There is an unexpected value"
                            + "returned as response from "
                            + "RIL_REQUEST_REGISTRATION_STATE.");
                    }
                } else {
                    throw new RuntimeException("Warning! Wrong number of parameters returned from "
                                         + "RIL_REQUEST_REGISTRATION_STATE: expected 14 got "
                                         + states.length);
                }

                mCdmaRoaming = regCodeIsRoaming(this.mRegistrationState);
                this.newCdmaDataConnectionState =
                    radioTechnologyToServiceState(responseValuesRegistrationState[0]);
                newSS.setState (regCodeToServiceState(this.mRegistrationState));
                newSS.setRadioTechnology(responseValuesRegistrationState[0]);
                newSS.setCssIndicator(responseValuesRegistrationState[4]);
                newSS.setSystemAndNetworkId(responseValuesRegistrationState[5],
                    responseValuesRegistrationState[6]);

                mRoamingIndicator = responseValuesRegistrationState[7];
                mIsInPrl = responseValuesRegistrationState[8];
                mDefaultRoamingIndicator = responseValuesRegistrationState[9];

                newNetworkType = responseValuesRegistrationState[0];

                // values are -1 if not available
                newCellLoc.setCellLocationData(responseValuesRegistrationState[1],
                                               responseValuesRegistrationState[2],
                                               responseValuesRegistrationState[3]);

                if (responseValuesRegistrationState[10] == 0) {
                    mRegistrationDeniedReason = ServiceStateTracker.REGISTRATION_DENIED_GEN;
                } else if (responseValuesRegistrationState[10] == 1) {
                    mRegistrationDeniedReason = ServiceStateTracker.REGISTRATION_DENIED_AUTH;
                } else {
                    mRegistrationDeniedReason = "";
                }

                if (mRegistrationState == 3) {
                    if (DBG) log("Registration denied, " + mRegistrationDeniedReason);
                }
                break;

            case EVENT_POLL_STATE_OPERATOR_CDMA: // Handle RIL_REQUEST_OPERATOR
                String opNames[] = (String[])ar.result;

                if (opNames != null && opNames.length >= 3) {
                    // TODO(Teleca): Is this necessary here and in the else clause?
                    newSS.setOperatorName(opNames[0], opNames[1], opNames[2]);
                    if (phone.mCM.getRadioState().isNVReady()) {
                        // In CDMA in case on NV the ss.mOperatorAlphaLong is set later with the
                        // ERI text, so here is ignored what is coming from the modem
                        newSS.setOperatorName(null, opNames[1], opNames[2]);
                    } else {
                        newSS.setOperatorName(opNames[0], opNames[1], opNames[2]);
                    }
                } else {
                    Log.w(LOG_TAG, "error parsing opNames");
                }
                break;

            case EVENT_POLL_STATE_CDMA_SUBSCRIPTION: // Handle RIL_CDMA_SUBSCRIPTION
                String cdmaSubscription[] = (String[])ar.result;

                if (cdmaSubscription != null && cdmaSubscription.length >= 4) {
                    mMdn = cdmaSubscription[0];
                    mHomeSystemId = Integer.parseInt(cdmaSubscription[1], 16);
                    mHomeNetworkId = Integer.parseInt(cdmaSubscription[2], 16);
                    mMin = cdmaSubscription[3];

                } else {
                    Log.w(LOG_TAG, "error parsing cdmaSubscription");
                }
                break;

            default:
                Log.e(LOG_TAG, "RIL response handle in wrong phone!"
                    + " Expected CDMA RIL request and get GSM RIL request.");
            break;
            }

        } catch (RuntimeException ex) {
            Log.e(LOG_TAG, "Exception while polling service state. "
                    + "Probably malformed RIL response.", ex);
        }

        pollingContext[0]--;

        if (pollingContext[0] == 0) {
            boolean namMatch = false;
            if ((mHomeSystemId != 0) && (mHomeSystemId == newSS.getSystemId()) ) {
                namMatch = true;
            }

            // Setting SS Roaming (general)
            if (isSubscriptionFromRuim) {
                newSS.setRoaming(isRoamingBetweenOperators(mCdmaRoaming, newSS));
            } else {
                newSS.setRoaming(mCdmaRoaming);
            }

            /**
             * TODO(Teleca): This would be simpler if mIsInPrl was a "boolean" as the
             * name implies rather than tri-state. Above I've suggested that the -1's
             * might be able to be removed, if so please simplify this. Otherwise change
             * the name to mPrlState or some such. Also the logic can be simplified
             * by testing for "mIsInPrl" only once.
             */
            // Setting SS CdmaRoamingIndicator and CdmaDefaultRoamingIndicator
            // TODO(Teleca): use constants for the standard roaming indicators
            if (mIsInPrl == 0 && mRegistrationState == 5) {
                // System is acquired but prl not loaded or no prl match
                newSS.setCdmaRoamingIndicator(2); //FLASHING
            } else if (!namMatch && (mIsInPrl == 1)) {
                // System is acquired, no nam match, prl match
                newSS.setCdmaRoamingIndicator(mRoamingIndicator);
            } else if (namMatch && (mIsInPrl == 1) && mRoamingIndicator <= 2) {
                // System is acquired, nam match, prl match, mRoamingIndicator <= 2
                newSS.setCdmaRoamingIndicator(1); //OFF
            } else if (namMatch && (mIsInPrl == 1) && mRoamingIndicator > 2) {
                // System is acquired, nam match, prl match, mRoamingIndicator > 2
                newSS.setCdmaRoamingIndicator(mRoamingIndicator);
            }
            newSS.setCdmaDefaultRoamingIndicator(mDefaultRoamingIndicator);

            // NOTE: Some operator may require to override the mCdmaRoaming (set by the modem)
            // depending on the mRoamingIndicator.

            if (DBG) {
                log("Set CDMA Roaming Indicator to: " + newSS.getCdmaRoamingIndicator()
                    + ". mCdmaRoaming = " + mCdmaRoaming + ",  namMatch = " + namMatch
                    + ", mIsInPrl= " + mIsInPrl + ", mRoamingIndicator = " + mRoamingIndicator
                    + ", mDefaultRoamingIndicator= " + mDefaultRoamingIndicator);
            }
            pollStateDone();
        }

    }

    private void setSignalStrengthDefaultValues() {
        mSignalStrength = new SignalStrength(99, -1, -1, -1, -1, -1, -1, false);
    }

    /**
     * A complete "service state" from our perspective is
     * composed of a handful of separate requests to the radio.
     *
     * We make all of these requests at once, but then abandon them
     * and start over again if the radio notifies us that some
     * event has changed
     */

    private void
    pollState() {
        pollingContext = new int[1];
        pollingContext[0] = 0;

        switch (cm.getRadioState()) {
        case RADIO_UNAVAILABLE:
            newSS.setStateOutOfService();
            newCellLoc.setStateInvalid();
            setSignalStrengthDefaultValues();
            mGotCountryCode = false;

            pollStateDone();
            break;

        case RADIO_OFF:
            newSS.setStateOff();
            newCellLoc.setStateInvalid();
            setSignalStrengthDefaultValues();
            mGotCountryCode = false;

            pollStateDone();
            break;

        case SIM_NOT_READY:
        case SIM_LOCKED_OR_ABSENT:
        case SIM_READY:
            log("Radio Technology Change ongoing, setting SS to off");
            newSS.setStateOff();
            newCellLoc.setStateInvalid();
            setSignalStrengthDefaultValues();
            mGotCountryCode = false;

            //NOTE: pollStateDone() is not needed in this case
            break;

        default:
            // Issue all poll-related commands at once
            // then count down the responses, which
            // are allowed to arrive out-of-order

            pollingContext[0]++;
            // RIL_REQUEST_CDMA_SUBSCRIPTION is necessary for CDMA
            cm.getCDMASubscription(
                    obtainMessage(EVENT_POLL_STATE_CDMA_SUBSCRIPTION, pollingContext));

            pollingContext[0]++;
            // RIL_REQUEST_OPERATOR is necessary for CDMA
            cm.getOperator(
                    obtainMessage(EVENT_POLL_STATE_OPERATOR_CDMA, pollingContext));

            pollingContext[0]++;
            // RIL_REQUEST_REGISTRATION_STATE is necessary for CDMA
            cm.getRegistrationState(
                    obtainMessage(EVENT_POLL_STATE_REGISTRATION_CDMA, pollingContext));

            break;
        }
    }

    private static String networkTypeToString(int type) {
        String ret = "unknown";

        switch (type) {
        case DATA_ACCESS_CDMA_IS95A:
        case DATA_ACCESS_CDMA_IS95B:
            ret = "CDMA";
            break;
        case DATA_ACCESS_CDMA_1xRTT:
            ret = "CDMA - 1xRTT";
            break;
        case DATA_ACCESS_CDMA_EvDo_0:
            ret = "CDMA - EvDo rev. 0";
            break;
        case DATA_ACCESS_CDMA_EvDo_A:
            ret = "CDMA - EvDo rev. A";
            break;
        default:
            if (DBG) {
                Log.e(LOG_TAG, "Wrong network. Can not return a string.");
            }
        break;
        }

        return ret;
    }

    private void
    pollStateDone() {
        if (DBG) log("Poll ServiceState done: oldSS=[" + ss + "] newSS=[" + newSS + "]");

        boolean hasRegistered =
            ss.getState() != ServiceState.STATE_IN_SERVICE
            && newSS.getState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered =
            ss.getState() == ServiceState.STATE_IN_SERVICE
            && newSS.getState() != ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionAttached =
            (this.cdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_1xRTT
                    && this.cdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_EVDO_0
                    && this.cdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_EVDO_A)
                    && (this.newCdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_1xRTT
                    || this.newCdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_EVDO_0
                    || this.newCdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_EVDO_A);

        boolean hasCdmaDataConnectionDetached =
            (this.cdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_1xRTT
                    || this.cdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_EVDO_0
                    || this.cdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_EVDO_A)
                    && (this.newCdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_1xRTT
                    && this.newCdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_EVDO_0
                    && this.newCdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_EVDO_A);

        boolean hasCdmaDataConnectionChanged =
                       cdmaDataConnectionState != newCdmaDataConnectionState;

        boolean hasNetworkTypeChanged = networkType != newNetworkType;

        boolean hasChanged = !newSS.equals(ss);

        boolean hasRoamingOn = !ss.getRoaming() && newSS.getRoaming();

        boolean hasRoamingOff = ss.getRoaming() && !newSS.getRoaming();

        boolean hasLocationChanged = !newCellLoc.equals(cellLoc);

        ServiceState tss;
        tss = ss;
        ss = newSS;
        newSS = tss;
        // clean slate for next time
        newSS.setStateOutOfService();

        CdmaCellLocation tcl = cellLoc;
        cellLoc = newCellLoc;
        newCellLoc = tcl;

        cdmaDataConnectionState = newCdmaDataConnectionState;
        networkType = newNetworkType;

        newSS.setStateOutOfService(); // clean slate for next time

        if (hasNetworkTypeChanged) {
            phone.setSystemProperty(PROPERTY_DATA_NETWORK_TYPE,
                    networkTypeToString(networkType));
        }

        if (hasRegistered) {
            Checkin.updateStats(phone.getContext().getContentResolver(),
                    Checkin.Stats.Tag.PHONE_CDMA_REGISTERED, 1, 0.0);
            networkAttachedRegistrants.notifyRegistrants();
        }

        if (hasChanged) {
            if (phone.mCM.getRadioState().isNVReady()) {
                String eriText;
                // Now the CDMAPhone sees the new ServiceState so it can get the new ERI text
                if (ss.getState() == ServiceState.STATE_IN_SERVICE) {
                    eriText = phone.getCdmaEriText();
                } else {
                    // Note that this is valid only for mRegistrationState 2,3,4, not 0!
                    /**
                     * TODO(Teleca): From the comment this apparently isn't always true
                     * should there be additional logic with other strings?
                     */
                    eriText = EriInfo.SEARCHING_TEXT;
                }
                ss.setCdmaEriText(eriText);
            }

            String operatorNumeric;

            phone.setSystemProperty(PROPERTY_OPERATOR_ALPHA,
                    ss.getOperatorAlphaLong());

            operatorNumeric = ss.getOperatorNumeric();
            phone.setSystemProperty(PROPERTY_OPERATOR_NUMERIC, operatorNumeric);

            if (operatorNumeric == null) {
                phone.setSystemProperty(PROPERTY_OPERATOR_ISO_COUNTRY, "");
            } else {
                String iso = "";
                try{
                    iso = MccTable.countryCodeForMcc(Integer.parseInt(
                            operatorNumeric.substring(0,3)));
                } catch ( NumberFormatException ex){
                    Log.w(LOG_TAG, "countryCodeForMcc error" + ex);
                } catch ( StringIndexOutOfBoundsException ex) {
                    Log.w(LOG_TAG, "countryCodeForMcc error" + ex);
                }

                phone.setSystemProperty(PROPERTY_OPERATOR_ISO_COUNTRY, iso);
                mGotCountryCode = true;
            }

            phone.setSystemProperty(PROPERTY_OPERATOR_ISROAMING,
                    ss.getRoaming() ? "true" : "false");

            updateSpnDisplay();
            phone.notifyServiceStateChanged(ss);
        }

        if (hasCdmaDataConnectionAttached) {
            cdmaDataConnectionAttachedRegistrants.notifyRegistrants();
        }

        if (hasCdmaDataConnectionDetached) {
            cdmaDataConnectionDetachedRegistrants.notifyRegistrants();
        }

        if (hasCdmaDataConnectionChanged) {
            phone.notifyDataConnection(null);
        }

        if (hasRoamingOn) {
            roamingOnRegistrants.notifyRegistrants();
        }

        if (hasRoamingOff) {
            roamingOffRegistrants.notifyRegistrants();
        }

        if (hasLocationChanged) {
            phone.notifyLocationChanged();
        }
    }

    /**
     * Returns a TimeZone object based only on parameters from the NITZ string.
     */
    private TimeZone getNitzTimeZone(int offset, boolean dst, long when) {
        TimeZone guess = findTimeZone(offset, dst, when);
        if (guess == null) {
            // Couldn't find a proper timezone.  Perhaps the DST data is wrong.
            guess = findTimeZone(offset, !dst, when);
        }
        if (DBG) log("getNitzTimeZone returning " + (guess == null ? guess : guess.getID()));
        return guess;
    }

    private TimeZone findTimeZone(int offset, boolean dst, long when) {
        int rawOffset = offset;
        if (dst) {
            rawOffset -= 3600000;
        }
        String[] zones = TimeZone.getAvailableIDs(rawOffset);
        TimeZone guess = null;
        Date d = new Date(when);
        for (String zone : zones) {
            TimeZone tz = TimeZone.getTimeZone(zone);
            if (tz.getOffset(when) == offset &&
                    tz.inDaylightTime(d) == dst) {
                guess = tz;
                break;
            }
        }

        return guess;
    }

    private void
    queueNextSignalStrengthPoll() {
        if (dontPollSignalStrength || (cm.getRadioState().isGsm())) {
            // The radio is telling us about signal strength changes
            // we don't have to ask it
            return;
        }

        Message msg;

        msg = obtainMessage();
        msg.what = EVENT_POLL_SIGNAL_STRENGTH;

        // TODO(Teleca): Don't poll signal strength if screen is off
        sendMessageDelayed(msg, POLL_PERIOD_MILLIS);
    }

    /**
     *  send signal-strength-changed notification if changed
     *  Called both for solicited and unsolicited signal stength updates
     */
    private void
    onSignalStrengthResult(AsyncResult ar) {
        SignalStrength oldSignalStrength = mSignalStrength;

        if (ar.exception != null) {
            // Most likely radio is resetting/disconnected change to default values.
            setSignalStrengthDefaultValues();
        } else {
            int[] ints = (int[])ar.result;
            int offset = 2;

            int cdmaDbm = (ints[offset] > 0) ? -ints[offset] : -1;
            int cdmaEcio = (ints[offset+1] > 0) ? -ints[offset+1] : -1;

            int evdoRssi = -1;
            int evdoEcio = -1;
            int evdoSnr = -1;
            if ((networkType == ServiceState.RADIO_TECHNOLOGY_EVDO_0)
                    || (networkType == ServiceState.RADIO_TECHNOLOGY_EVDO_A)) {
                evdoRssi = (ints[offset+2] > 0) ? -ints[offset+2] : -1;
                evdoEcio = (ints[offset+3] > 0) ? -ints[offset+3] : -1;
                evdoSnr  = ((ints[offset+4] > 0) && (ints[offset+4] <= 8)) ? ints[offset+4] : -1;
            }

            mSignalStrength = new SignalStrength(99, -1, cdmaDbm, cdmaEcio,
                    evdoRssi, evdoEcio, evdoSnr, false);
        }

        if (!mSignalStrength.equals(oldSignalStrength)) {
            try { // This takes care of delayed EVENT_POLL_SIGNAL_STRENGTH (scheduled after
                  // POLL_PERIOD_MILLIS) during Radio Technology Change)
                phone.notifySignalStrength();
           } catch (NullPointerException ex) {
                log("onSignalStrengthResult() Phone already destroyed: " + ex
                        + "SignalStrength not notified");
           }
        }
    }


    private int radioTechnologyToServiceState(int code) {
        int retVal = ServiceState.RADIO_TECHNOLOGY_UNKNOWN;
        switch(code) {
        case 0:
        case 1:
        case 2:
        case 3:
        case 4:
        case 5:
            break;
        case 6:
            retVal = ServiceState.RADIO_TECHNOLOGY_1xRTT;
            break;
        case 7:
            retVal = ServiceState.RADIO_TECHNOLOGY_EVDO_0;
            break;
        case 8:
            retVal = ServiceState.RADIO_TECHNOLOGY_EVDO_A;
            break;
        default:
            Log.e(LOG_TAG, "Wrong radioTechnology code.");
        break;
        }
        return(retVal);
    }

    /** code is registration state 0-5 from TS 27.007 7.2 */
    private int
    regCodeToServiceState(int code) {
        switch (code) {
        case 0: // Not searching and not registered
            return ServiceState.STATE_OUT_OF_SERVICE;
        case 1:
            return ServiceState.STATE_IN_SERVICE;
        case 2: // 2 is "searching", fall through
        case 3: // 3 is "registration denied", fall through
        case 4: // 4 is "unknown" no vaild in current baseband
            return ServiceState.STATE_OUT_OF_SERVICE;
        case 5:// 5 is "Registered, roaming"
            return ServiceState.STATE_IN_SERVICE;

        default:
            Log.w(LOG_TAG, "unexpected service state " + code);
        return ServiceState.STATE_OUT_OF_SERVICE;
        }
    }

    /**
     * @return The current CDMA data connection state. ServiceState.RADIO_TECHNOLOGY_1xRTT or
     * ServiceState.RADIO_TECHNOLOGY_EVDO is the same as "attached" and
     * ServiceState.RADIO_TECHNOLOGY_UNKNOWN is the same as detached.
     */
    /*package*/ int getCurrentCdmaDataConnectionState() {
        return cdmaDataConnectionState;
    }

    /**
     * code is registration state 0-5 from TS 27.007 7.2
     * returns true if registered roam, false otherwise
     */
    private boolean
    regCodeIsRoaming (int code) {
        // 5 is  "in service -- roam"
        return 5 == code;
    }

    /**
     * Set roaming state when cdmaRoaming is true and ons is different from spn
     * @param cdmaRoaming TS 27.007 7.2 CREG registered roaming
     * @param s ServiceState hold current ons
     * @return true for roaming state set
     */
    private
    boolean isRoamingBetweenOperators(boolean cdmaRoaming, ServiceState s) {
        String spn = SystemProperties.get(PROPERTY_ICC_OPERATOR_ALPHA, "empty");

        // NOTE: in case of RUIM we should completely ignore the ERI data file and
        // mOperatorAlphaLong is set from RIL_REQUEST_OPERATOR response 0 (alpha ONS)
        String onsl = s.getOperatorAlphaLong();
        String onss = s.getOperatorAlphaShort();

        boolean equalsOnsl = onsl != null && spn.equals(onsl);
        boolean equalsOnss = onss != null && spn.equals(onss);

        return cdmaRoaming && !(equalsOnsl || equalsOnss);
    }

    private boolean getAutoTime() {
        try {
            return Settings.System.getInt(phone.getContext().getContentResolver(),
                    Settings.System.AUTO_TIME) > 0;
        } catch (SettingNotFoundException snfe) {
            return true;
        }
    }

    /**
     * @return true if phone is camping on a technology
     * that could support voice and data simultaneously.
     */
    boolean isConcurrentVoiceAndData() {

        // Note: it needs to be confirmed which CDMA network types
        // can support voice and data calls concurrently.
        // For the time-being, the return value will be false.
        return false;
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "[CdmaServiceStateTracker] " + s);
    }

}
