/*
 * Copyright © 2020, The Gust Framework Authors. All rights reserved.
 *
 * The Gust/Elide framework and tools, and all associated source or object computer code, except where otherwise noted,
 * are licensed under the Zero Prosperity license, which is enclosed in this repository, in the file LICENSE.txt. Use of
 * this code in object or source form requires and implies consent and agreement to that license in principle and
 * practice. Source or object code not listing this header, or unless specified otherwise, remain the property of
 * Elide LLC and its suppliers, if any. The intellectual and technical concepts contained herein are proprietary to
 * Elide LLC and its suppliers and may be covered by U.S. and Foreign Patents, or patents in process, and are protected
 * by trade secret and copyright law. Dissemination of this information, or reproduction of this material, in any form,
 * is strictly forbidden except in adherence with assigned license requirements.
 */

/**
 * Gust: Frontend boot handler.
 *
 * @fileoverview Provides a handler which runs on `window.load`, which preps framework resources and executes registered
 *  user logic init code.
 */

/*global goog*/
goog.module('gust.boot');

const Core = goog.require('gust.Core');
const logging = goog.require('gust.logging');


/**
 * Main function, dispatched on page load.
 *
 * @private
 */
function main() {
    logging.log(`Hello from Gust, version ${Core.getVersion()}!`);
}

// Bind `main` to page load.
window.addEventListener('load', main, {});
