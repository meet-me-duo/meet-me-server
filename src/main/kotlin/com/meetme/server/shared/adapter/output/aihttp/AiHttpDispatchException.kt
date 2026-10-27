package com.meetme.server.shared.adapter.output.aihttp

import java.io.IOException

/** Static, nonretryable admission failure. Provider HTTP status retains its own failure mapping. */
class AiHttpDispatchException : IOException("CONFIGURATION")
