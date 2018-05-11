/**
 * Tell Google Closure compiler not to munge tus.Upload name. We are
 * pulling the tus-js-client library from a CDN instead of adding it
 * to our code base.
 *
 * @fileoverview This is an externs file.
 * @externs
 */

/**
 * Figwheel expects files with .js extension inside its source
 * directories to be a foreign library. And foreign libraries *MUST*
 * declare a namespace. In fact, figwheel assumes it, and if it
 * doesn't find it and can't map the file back to a source .cljs file,
 * it bombs out with a NullPointerException.
 *
 * So even if this is *NOT* a foreign library, but just an externs file,
 * add a namespace declaration to prevent figwheel from crashing.
 */
goog.provide('tus.Upload');

/**
 * From here below, it's just regular externs file declarations.
 */
var tus;

/**
 * @constructor
 */
tus.Upload = function(){};

/** */
tus.Upload.prototype.start = function(){};

tus.Upload.options.onSuccess = function(){};

