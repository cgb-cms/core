import { jitsuClient, JitsuClient } from '@jitsu/sdk-js';

import {
    API_EXPERIMENTS_URL,
    DEBUG_LEVELS,
    EXPERIMENT_DB_KEY_PATH,
    EXPERIMENT_DB_STORE_NAME
} from './constants';
import {
    AssignedExperiments,
    DotExperimentConfig,
    ExperimentParsed,
    IsUserIncludedApiResponse
} from './models';
import { parseDataForAnalytics } from './parser/parser';
import { IndexDBDatabaseHandler } from './persistence/index-db-database-handler';
import { dotLogger } from './utils/utils';

/**
 * `DotExperiments` is a Typescript class to handles all operations related to fetching, storing, parsing, and navigating
 * data for Experiments (A/B Testing).
 *
 * It requires a configuration object for instantiation, please instance it using the method `getInstance` sending
 * an object with `api-key`, `server` and `debug`.
 *
 * Here's an example of how you can instantiate DotExperiments class:
 * @example
 * ```typescript
 * const instance = DotExperiments.getInstance({
 *   server: "yourServerUrl",
 *   "api-key": "yourApiKey"
 * });
 * ```
 *
 * @export
 * @class DotExperiments
 *
 */
export class DotExperiments {
    private static instance: DotExperiments;
    private databaseHandler!: IndexDBDatabaseHandler;
    private analytics!: JitsuClient;

    private constructor(private config: DotExperimentConfig) {
        if (!this.config['server']) {
            throw new Error('`server` must be provided and should not be empty!');
        }

        if (!this.config['api-key']) {
            throw new Error('`api-key` must be provided and should not be empty!');
        }

        dotLogger(
            `DotExperiments instanced with ${JSON.stringify(config)} configuration`,
            this.getIsDebugActive()
        );
    }

    /**
     * Retrieves instance of DotExperiments class if doesn't exist create a new one.
     * If the instance does not exist, it creates a new instance with the provided configuration and calls the `getExperimentData` method.
     *
     * @param {DotExperimentConfig} config - The configuration object for initializing the DotExperiments instance.
     * @return {DotExperiments} - The instance of the DotExperiments class.
     */
    public static getInstance(config?: DotExperimentConfig): DotExperiments {
        if (!DotExperiments.instance) {
            if (!config) {
                throw new Error('Configuration is required to create a new instance.');
            }

            DotExperiments.instance = new DotExperiments(config);

            // Steps
            this.instance.initializeDatabaseHandler();
            this.instance.setExperimentData();
            this.instance.initAnalyticsClient();
        }

        return DotExperiments.instance;
    }

    /**
     * Retrieves the current debug status.
     *
     * @private
     * @returns {boolean} - The debug status.
     */
    getIsDebugActive(): boolean {
        return this.config.debug;
    }

    /**
     * Fetches experiments from the server.
     *
     * @private
     * @returns {Promise<AssignedExperiments>} - The entity object returned from the server.
     * @throws {Error} - If an HTTP error occurs or an error occurs during the fetch request.
     */
    private async getExperimentsFromServer(): Promise<AssignedExperiments> {
        try {
            const response: Response = await fetch(`${this.config.server}${API_EXPERIMENTS_URL}`, {
                method: 'POST',
                headers: {
                    Accept: 'application/json',
                    'Content-Type': 'application/json'
                }
            });

            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }

            const responseJson = (await response.json()) as IsUserIncludedApiResponse;
            dotLogger(`Experiment data get successfully `, this.getIsDebugActive());

            return responseJson.entity;
        } catch (error) {
            throw new Error(`An error occurred while trying to fetch the experiments: ${error}`);
        }
    }

    /**
     * Retrieves experiment data from the server.
     *
     * @private
     * @async
     * @throws {Error} If an error occurs while loading the experiments.
     */
    private async setExperimentData() {
        try {
            const experimentAssignedToUser = await this.getExperimentsFromServer();
            this.persistExperiments(experimentAssignedToUser);
        } catch (e) {
            throw Error(`Error persisting experiments to indexDB, ${e}`);
        }
    }

    /**
     * Persists experiments to the IndexDB.
     *
     * @param {AssignedExperiments} entity - The entity containing experiments to persist.
     * @private
     */
    private persistExperiments(entity: AssignedExperiments) {
        if (!entity.experiments.length) {
            return;
        }

        this.databaseHandler
            .persistData(entity)
            .then(() => {
                dotLogger('Experiment data stored successfully', this.getIsDebugActive());
            })
            .catch((onerror) => {
                dotLogger(`Error storing data. ${onerror}`, this.getIsDebugActive());
            });
    }

    /**
     * Initializes the database handler.
     *
     * This private method instantiates the class handling the IndexDB database
     * and assigns this instance to 'databaseHandler'.
     *
     * @private
     */
    private initializeDatabaseHandler() {
        this.databaseHandler = new IndexDBDatabaseHandler({
            db_store: EXPERIMENT_DB_STORE_NAME,
            db_name: EXPERIMENT_DB_STORE_NAME,
            db_key_path: EXPERIMENT_DB_KEY_PATH
        });
    }

    /**
     * Retrieves and parses data from the database for analytics.
     *
     * This private method fetches data from the IndexDB database and
     * parses it into the format needed for analytics when saving events.
     *
     * @private
     * @returns {Promise<ExperimentParsed>} A Promise that resolves to the data parsed for analytics.
     */
    private async getDataForAnalytics(): Promise<ExperimentParsed> {
        const data = await this.databaseHandler.getData<AssignedExperiments>();

        return parseDataForAnalytics(data, location);
    }

    /**
     * Initializes the Jitsu analytics client.
     *
     * This private method sets up the Jitsu client responsible for sending events
     * to the server with the provided configuration. It also uses the parsed data
     * and registers it as global within Jitsu.
     *
     * @private
     */
    private async initAnalyticsClient() {
        try {
            this.analytics = jitsuClient({
                key: this.config['api-key'],
                tracking_host: this.config['server'],
                log_level: this.config['debug'] ? DEBUG_LEVELS.DEBUG : DEBUG_LEVELS.WARN
            });

            const { experiments } = await this.getDataForAnalytics();
            this.analytics.set({ experiments });

            dotLogger(`Analytics client created successfully.`, this.getIsDebugActive());
        } catch (error) {
            throw Error(`Error creating analytics client, ${error}`);
        }
    }
}
