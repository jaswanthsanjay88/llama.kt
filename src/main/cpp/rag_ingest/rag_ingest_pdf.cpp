#include "rag_ingest.h"

int rag_ingest_extract_pdf(const uint8_t* data, size_t size, std::string& out) {
    (void)data;
    (void)size;
    out.clear();
    return RAG_INGEST_ERR_UNSUPPORTED;
}
