'use strict';

const LABEL = '[sf-cloud]';
const STRAY_AMPERSAND = /&(?!(?:#[0-9]+|#x[0-9a-fA-F]+|[A-Za-z][A-Za-z0-9._-]*);)/g;

const reason = (error) => (error && error.message ? error.message : String(error)).replace(/\s+/g, ' ').trim();

process.on('unhandledRejection', (error) => {
    console.error(LABEL + ' ignored an unhandled rejection: ' + reason(error));
});

try {
    const xml2js = require('xml2js');
    const parseStringPromise = xml2js.Parser.prototype.parseStringPromise;
    xml2js.Parser.prototype.parseStringPromise = function (input) {
        const options = this.options;
        return parseStringPromise.call(this, input).catch((error) => {
            const text = input === null || input === undefined ? '' : input.toString();
            const repaired = text.replace(STRAY_AMPERSAND, '&amp;');
            const retry =
                repaired === text
                    ? Promise.reject(error)
                    : new xml2js.Parser(options).parseStringPromise(repaired);
            return retry.catch(() => {
                console.error(LABEL + ' skipped malformed XML: ' + reason(error));
                return {};
            });
        });
    };
} catch (error) {
    console.error(LABEL + ' cannot guard xml2js: ' + reason(error));
}

try {
    const { BaseWorkspaceContext } = require('@salesforce/lightning-lsp-common');
    const writeSettings = BaseWorkspaceContext.prototype.writeSettings;
    BaseWorkspaceContext.prototype.writeSettings = function () {
        return this.type === 'CORE_ALL' || this.type === 'CORE_PARTIAL'
            ? writeSettings.call(this)
            : Promise.resolve();
    };
} catch (error) {
    console.error(LABEL + ' cannot guard workspace settings: ' + reason(error));
}
