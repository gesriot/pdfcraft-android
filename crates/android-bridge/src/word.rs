//! Word export has its own immutable PDF snapshot. Each call converts one page.
use super::*;
use pdfcraft_engine::word_export::WordExport;

#[derive(uniffi::Object)]
pub struct WordExportJob {
    job: Mutex<Option<WordExport>>,
}

impl WordExportJob {
    fn guarded<T>(&self, f: impl FnOnce(&mut Option<WordExport>) -> Result<T, MobileError>) -> Result<T, MobileError> {
        catch_unwind(AssertUnwindSafe(|| {
            let mut guard = self.job.lock().map_err(|_| MobileError::WorkerFailed)?;
            f(&mut guard)
        }))
        .unwrap_or(Err(MobileError::WorkerFailed))
    }
}

#[uniffi::export]
impl WordExportJob {
    pub fn advance(&self) -> Result<u32, MobileError> {
        self.guarded(|job| Ok(job.as_mut().ok_or(MobileError::Closed)?.advance().map_err(engine_error)? as u32))
    }
    /// New local staging path only; source never changes. Returns rasterized zero-based pages.
    pub fn finish(&self, path: String) -> Result<Vec<u32>, MobileError> {
        self.guarded(|slot| {
            let job = slot.as_ref().ok_or(MobileError::Closed)?;
            let bytes = job.finish().map_err(engine_error)?;
            super::pages::write_new(&path, &bytes)?;
            let pages = job.image_pages.iter().map(|p| *p as u32).collect();
            *slot = None;
            Ok(pages)
        })
    }
    pub fn release(&self) -> Result<(), MobileError> {
        self.guarded(|job| {
            *job = None;
            Ok(())
        })
    }
}

#[uniffi::export]
impl MobileDocument {
    pub fn begin_word_export(&self, pages: Vec<u32>, revision: u64) -> Result<Arc<WordExportJob>, MobileError> {
        self.with_state(|s| {
            let doc = s.session.get(s.id).ok_or(MobileError::Closed)?;
            if doc.edit_generation() != revision {
                return Err(invalid("Документ изменился. Выберите страницы заново"));
            }
            let pages = pages.iter().map(|p| *p as usize).collect::<Vec<_>>();
            let job = doc.prepare_word_export(&pages).map_err(engine_error)?;
            Ok(Arc::new(WordExportJob { job: Mutex::new(Some(job)) }))
        })
    }
}

#[cfg(test)]
mod tests;
