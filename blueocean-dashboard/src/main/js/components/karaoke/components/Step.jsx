import React, { Component, PropTypes } from 'react';
import { ResultItem, TimeDuration } from '@jenkins-cd/design-language';
import { AppConfig, logging, TimeManager } from '@jenkins-cd/blueocean-core-js';
import { observer } from 'mobx-react';
import { KaraokeService } from '../index';
import LogConsole from './LogConsole';
import InputStep from './InputStep';
import { prefixIfNeeded } from '../urls/prefixIfNeeded';

const logger = logging.logger('io.jenkins.blueocean.dashboard.karaoke.Step');
const timeManager = new TimeManager();

function createStepLabel(step) {
    const { displayName, displayDescription } = step;

    if (displayDescription) {
        return [
            <span className="result-item-label-desc" title={displayDescription}>{displayDescription}</span>,
            <span className="result-item-label-name">&mdash; {displayName}</span>,
        ];
    }

    return displayName;
}

@observer
export class Step extends Component {
    constructor(props) {
        super(props);

        const { augmenter, step } = props;
        const oldIsFocused = step.isFocused;
        const newIsFocused = this.isFocused(props);

        // if we are called with anchor that means that we need to fetch the log to display it
        this.pager = KaraokeService.logPager(augmenter, { ...step, isFocused: newIsFocused });
        logger.debug('isFocused initial', oldIsFocused, 'after', newIsFocused);

        this.state = {
            expanded: newIsFocused,
        };
    }

    /**
     * Mainly implemented because
     * - we need to create a reference time for running jobs
     */
    componentWillMount() {
        const { step } = this.props;
        // needed for running steps as reference
        this.durationInMillis = (this.durationHarmonize(step)).durationInMillis;
        logger.debug('durationInMillis mounting', this.durationInMillis);
    }
    /**
     * Mainly implemented due to fetch full log `start=0` for a step
     * @param nextProps
     */
    componentWillReceiveProps(nextProps) {
        const nextStart = nextProps.location && nextProps.location.query ? nextProps.location.query.start : undefined;
        const currentStart = this.props.location && this.props.location.query ? this.props.location.query.start : undefined;
        logger.debug('newProps mate', nextStart, currentStart);
        if (currentStart !== nextStart && nextStart !== undefined) {
            logger.debug('re-fetching since result changed and we want to display the full log');
            this.pager.fetchLog({ url: nextProps.step.logUrl, start: nextStart });
        }
    }
    /*
     * Calculate whether we need to expand the step due to linking.
     * When we trigger a log-0 that means we want to see the full log
     */
    isFocused(props, state = {}) {
        const { step, location: { hash: anchorName } } = props;
        const stateFocus = state.expanded;

        let isFocused = false;

        if (stateFocus !== undefined) {
            // Setting the state via interaction overrides any defaults
            isFocused = stateFocus;
        } else if (step && (step.isInputStep || step.result === 'FAILURE')) {
            // Input and failure steps should be opened by default
            isFocused = true;
        } else if (props.tailLogs && step && step.isFocused) {
            // In karaoke + log tailing mode + currently running step
            isFocused = true;
        }

        // e.g. #step-10-log-1 or #step-10
        if (anchorName) {
            logger.debug('expandAnchor', anchorName);
            const stepReg = /step-([0-9]{1,})?($|-log-([0-9]{1,})$)/;
            const match = stepReg.exec(anchorName);

            if (match && match[1] && match[1] === step.id) {
                isFocused = true;
            }
        }
        return isFocused || false;
    }

    // needed to calculated running times
    durationHarmonize(step) {
        const skewMillis = AppConfig.getServerBrowserTimeSkewMillis();
        // the time when we started the run harmonized with offset
        return timeManager.harmonizeTimes({ ...step }, skewMillis);
    }

    render() {
        const { step, locale, router, location, t, scrollToBottom } = this.props;
        if (step === undefined || !step) {
            return null;
        }
        const { durationInMillis } = this.durationHarmonize(step);
        const isFocused = this.isFocused(this.props, this.state);
        const { data: logArray, hasMore } = this.pager.log || {};
        let children = null;
        if (logArray && !step.isInputStep) {
            const currentLogUrl = prefixIfNeeded(this.pager.currentLogUrl);
            logger.debug('Updating children');
            children = (<LogConsole {...{
                t,
                router,
                location,
                hasMore,
                scrollToBottom,
                logArray,
                currentLogUrl,
                key: step.logUrl,
                prefix: `step-${step.id}-`,
            }}
            />);
        } else if (step.isInputStep) {
            children = <InputStep {...{ step, key: 'step' }} />;
        } else if (!logArray && step.hasLogs) {
            children = <span key={'span'}>&nbsp;</span>;
        }
        const getLogForNode = () => {
            if (this.pager.log === undefined || (this.pager.log && !this.pager.log.data)) {
                const cfg = { url: step.logUrl };
                logger.debug('getLogForNode called will fetch now with cfg.', cfg);
                this.pager.fetchLog(cfg);
                // we are now want to expand the result item
                this.setState({ expanded: true });
            }
            if (this.props.onUserExpand) {
                this.props.onUserExpand(step);
            }
        };
        const removeFocus = () => {
            this.setState({ expanded: false });
            // we need to remove the hash on collapse otherwise the result item will not be collapsed
            if (location.hash) {
                delete location.hash;
                router.push(location);
            }
            if (this.props.onUserCollapse) {
                this.props.onUserCollapse(step);
            }
        };
        // some ATH hook enhancements
        const logConsoleClass = `logConsole step-${step.id}`;
        // duration calculations
        const duration = step.isRunning ? this.durationInMillis : durationInMillis;
        logger.debug('duration', duration, step.isRunning);
        const time = (<TimeDuration
            millis={duration }
            liveUpdate={step.isRunning}
            updatePeriod={1000}
            locale={locale}
            t={t}
        />);

        return (<div className={logConsoleClass}>
            <ResultItem {...{
                extraInfo: time,
                key: step.key,
                result: step.computedResult.toLowerCase(),
                expanded: isFocused,
                label: createStepLabel(step),
                onCollapse: removeFocus,
                onExpand: getLogForNode,
            }}
            >
                { children }
            </ResultItem>
        </div>);
    }
}

Step.propTypes = {
    augmenter: PropTypes.object,
    step: PropTypes.object.isRequired,
    location: PropTypes.object,
    router: PropTypes.shape,
    locale: PropTypes.object,
    t: PropTypes.func,
    scrollToBottom: PropTypes.bool,
    onUserExpand: PropTypes.func,
    onUserCollapse: PropTypes.func,
    tailLogs: PropTypes.bool,
};
